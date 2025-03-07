/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.indexing.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.inject.Inject;
import org.apache.druid.common.guava.FutureUtils;
import org.apache.druid.concurrent.LifecycleLock;
import org.apache.druid.indexer.TaskLocation;
import org.apache.druid.indexer.TaskStatus;
import org.apache.druid.indexing.common.config.TaskConfig;
import org.apache.druid.indexing.common.task.Task;
import org.apache.druid.indexing.overlord.TaskRunner;
import org.apache.druid.indexing.overlord.TaskRunnerListener;
import org.apache.druid.java.util.common.Either;
import org.apache.druid.java.util.common.FileUtils;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.common.lifecycle.LifecycleStart;
import org.apache.druid.java.util.common.lifecycle.LifecycleStop;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.rpc.HttpResponseException;
import org.apache.druid.rpc.indexing.OverlordClient;
import org.apache.druid.server.coordination.ChangeRequestHistory;
import org.apache.druid.server.coordination.ChangeRequestsSnapshot;
import org.apache.druid.server.metrics.IndexerTaskCountStatsProvider;
import org.apache.druid.utils.CollectionUtils;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * This class manages the list of tasks assigned to this worker.
 * <p>
 * It persists the list of assigned and completed tasks on disk. assigned task from disk is deleted as soon as it
 * starts running and completed task on disk is deleted based on a periodic schedule where overlord is asked for
 * active tasks to see which completed tasks are safe to delete.
 */
public class WorkerTaskManager implements IndexerTaskCountStatsProvider
{
  private static final EmittingLogger log = new EmittingLogger(WorkerTaskManager.class);

  private final ObjectMapper jsonMapper;
  private final TaskRunner taskRunner;
  private final ExecutorService exec;

  private final LifecycleLock lifecycleLock = new LifecycleLock();

  private final ConcurrentMap<String, Task> assignedTasks = new ConcurrentHashMap<>();

  // ZK_CLEANUP_TODO : these are marked protected to be used in subclass WorkerTaskMonitor that updates ZK.
  // should be marked private alongwith WorkerTaskMonitor removal.
  protected final ConcurrentMap<String, TaskDetails> runningTasks = new ConcurrentHashMap<>();
  protected final ConcurrentMap<String, TaskAnnouncement> completedTasks = new ConcurrentHashMap<>();

  private final ChangeRequestHistory<WorkerHistoryItem> changeHistory = new ChangeRequestHistory<>();

  //synchronizes access to "running", "completed" and "changeHistory"
  protected final Object lock = new Object();

  private final ScheduledExecutorService completedTasksCleanupExecutor;

  private final AtomicBoolean disabled = new AtomicBoolean(false);

  private final OverlordClient overlordClient;
  private final File storageDir;

  @Inject
  public WorkerTaskManager(
      ObjectMapper jsonMapper,
      TaskRunner taskRunner,
      TaskConfig taskConfig,
      OverlordClient overlordClient
  )
  {
    this.jsonMapper = jsonMapper;
    this.taskRunner = taskRunner;
    this.exec = Execs.singleThreaded("WorkerTaskManager-NoticeHandler");
    this.completedTasksCleanupExecutor = Execs.scheduledSingleThreaded("WorkerTaskManager-CompletedTasksCleaner");
    this.overlordClient = overlordClient;

    storageDir = taskConfig.getBaseTaskDir();
  }

  @LifecycleStart
  public void start() throws Exception
  {
    if (!lifecycleLock.canStart()) {
      throw new ISE("can't start.");
    }

    synchronized (lock) {
      try {
        log.debug("Starting...");
        cleanupAndMakeTmpTaskDir();
        registerLocationListener();
        restoreRestorableTasks();
        initAssignedTasks();
        initCompletedTasks();
        scheduleCompletedTasksCleanup();
        lifecycleLock.started();
        log.debug("Started.");
      }
      catch (Exception e) {
        log.makeAlert(e, "Exception starting WorkerTaskManager.").emit();
        throw e;
      }
      finally {
        lifecycleLock.exitStart();
      }
    }
  }

  @LifecycleStop
  public void stop() throws Exception
  {
    if (!lifecycleLock.canStop()) {
      throw new ISE("can't stop.");
    }

    synchronized (lock) {
      try {
        // When stopping, the task status should not be communicated to the overlord, so the listener and exec
        // are shut down before the taskRunner is stopped.
        taskRunner.unregisterListener("WorkerTaskManager");
        exec.shutdownNow();
        taskRunner.stop();
        log.debug("Stopped WorkerTaskManager.");
      }
      catch (Exception e) {
        log.makeAlert(e, "Exception stopping WorkerTaskManager")
           .emit();
      }
    }
  }

  public Map<String, TaskAnnouncement> getCompletedTasks()
  {
    return completedTasks;
  }

  private void submitNoticeToExec(Notice notice)
  {
    exec.execute(
        () -> {
          try {
            notice.handle();
          }
          catch (Exception e) {
            if (e instanceof InterruptedException) {
              Thread.currentThread().interrupt();
            }

            log.makeAlert(e, "Failed to handle notice")
               .addData("noticeClass", notice.getClass().getSimpleName())
               .addData("noticeTaskId", notice.getTaskId())
               .emit();
          }
        }
    );
  }

  private void restoreRestorableTasks()
  {
    final List<Pair<Task, ListenableFuture<TaskStatus>>> restored = taskRunner.restore();
    for (Pair<Task, ListenableFuture<TaskStatus>> pair : restored) {
      addRunningTask(pair.lhs, pair.rhs);
    }
  }

  private void registerLocationListener()
  {
    taskRunner.registerListener(
        new TaskRunnerListener()
        {
          @Override
          public String getListenerId()
          {
            return "WorkerTaskManager";
          }

          @Override
          public void locationChanged(final String taskId, final TaskLocation newLocation)
          {
            submitNoticeToExec(new LocationNotice(taskId, newLocation));
          }

          @Override
          public void statusChanged(final String taskId, final TaskStatus status)
          {
            // do nothing
          }
        },
        Execs.directExecutor()
    );
  }

  private void addRunningTask(final Task task, final ListenableFuture<TaskStatus> future)
  {
    runningTasks.put(task.getId(), new TaskDetails(task));
    Futures.addCallback(
        future,
        new FutureCallback<>()
        {
          @Override
          public void onSuccess(TaskStatus result)
          {
            submitNoticeToExec(new StatusNotice(task, result));
          }

          @Override
          public void onFailure(Throwable t)
          {
            submitNoticeToExec(
                new StatusNotice(
                    task,
                    TaskStatus.failure(
                        task.getId(),
                        "Failed to run task with an exception. See middleManager or indexer logs for more details."
                    )
                )
            );
          }
        },
        MoreExecutors.directExecutor()
    );
  }

  public void assignTask(Task task)
  {
    Preconditions.checkState(lifecycleLock.awaitStarted(1, TimeUnit.SECONDS), "not started");

    synchronized (lock) {
      if (assignedTasks.containsKey(task.getId())
          || runningTasks.containsKey(task.getId())
          || completedTasks.containsKey(task.getId())) {
        log.warn("Request to assign task[%s] ignored because it exists already.", task.getId());
        return;
      }

      try {
        FileUtils.writeAtomically(
            new File(getAssignedTaskDir(), task.getId()),
            getTmpTaskDir(),
            os -> {
              jsonMapper.writeValue(os, task);
              return null;
            }
        );
        assignedTasks.put(task.getId(), task);
      }
      catch (IOException ex) {
        log.error(ex, "Error while trying to persist assigned task[%s]", task.getId());
        throw new ISE("Assign Task[%s] Request failed because [%s].", task.getId(), ex.getMessage());
      }

      changeHistory.addChangeRequest(
          new WorkerHistoryItem.TaskUpdate(
              TaskAnnouncement.create(
                  task,
                  TaskStatus.running(task.getId()),
                  TaskLocation.unknown()
              )
          )
      );
    }

    submitNoticeToExec(new RunNotice(task));
  }

  private File getTmpTaskDir()
  {
    return new File(storageDir, "workerTaskManagerTmp");
  }

  private void cleanupAndMakeTmpTaskDir() throws IOException
  {
    File tmpDir = getTmpTaskDir();
    FileUtils.mkdirp(tmpDir);
    if (!tmpDir.isDirectory()) {
      throw new ISE("Tmp Tasks Dir [%s] does not exist/not-a-directory.", tmpDir);
    }

    // Delete any tmp files left out from before due to jvm crash.
    try {
      org.apache.commons.io.FileUtils.cleanDirectory(tmpDir);
    }
    catch (IOException ex) {
      log.warn("Failed to cleanup tmp dir [%s].", tmpDir.getAbsolutePath());
    }
  }

  public File getAssignedTaskDir()
  {
    return new File(storageDir, "assignedTasks");
  }

  private void initAssignedTasks() throws IOException
  {
    File assignedTaskDir = getAssignedTaskDir();

    log.debug("Looking for any previously assigned tasks on disk[%s].", assignedTaskDir);

    FileUtils.mkdirp(assignedTaskDir);

    for (File taskFile : assignedTaskDir.listFiles()) {
      try {
        String taskId = taskFile.getName();
        Task task = jsonMapper.readValue(taskFile, Task.class);
        if (taskId.equals(task.getId())) {
          assignedTasks.put(taskId, task);
        } else {
          throw new ISE("Corrupted assigned task on disk[%s].", taskFile.getAbsoluteFile());
        }
      }
      catch (IOException ex) {
        log.noStackTrace()
           .error(ex, "Failed to read assigned task from disk at [%s]. Ignored.", taskFile.getAbsoluteFile());
      }
    }

    if (!assignedTasks.isEmpty()) {
      log.info(
          "Found %,d running tasks from previous run: %s",
          assignedTasks.size(),
          assignedTasks.values().stream().map(Task::getId).collect(Collectors.joining(", "))
      );
    }

    for (Task task : assignedTasks.values()) {
      submitNoticeToExec(new RunNotice(task));
    }
  }

  private void cleanupAssignedTask(Task task)
  {
    assignedTasks.remove(task.getId());
    File taskFile = new File(getAssignedTaskDir(), task.getId());
    try {
      Files.delete(taskFile.toPath());
    }
    catch (IOException ex) {
      log.error(ex, "Failed to delete assigned task from disk at [%s].", taskFile);
    }
  }

  public ListenableFuture<ChangeRequestsSnapshot<WorkerHistoryItem>> getChangesSince(ChangeRequestHistory.Counter counter)
  {
    Preconditions.checkState(lifecycleLock.awaitStarted(1, TimeUnit.SECONDS), "not started");

    if (counter.getCounter() < 0) {
      synchronized (lock) {

        List<WorkerHistoryItem> items = new ArrayList<>();
        items.add(new WorkerHistoryItem.Metadata(disabled.get()));

        for (Task task : assignedTasks.values()) {
          items.add(
              new WorkerHistoryItem.TaskUpdate(
                  TaskAnnouncement.create(
                      task,
                      TaskStatus.running(task.getId()),
                      TaskLocation.unknown()
                  )
              )
          );
        }

        for (TaskDetails details : runningTasks.values()) {
          items.add(
              new WorkerHistoryItem.TaskUpdate(
                  TaskAnnouncement.create(
                      details.task,
                      details.status,
                      details.location
                  )
              )
          );
        }

        for (TaskAnnouncement taskAnnouncement : completedTasks.values()) {
          items.add(new WorkerHistoryItem.TaskUpdate(taskAnnouncement));
        }

        SettableFuture<ChangeRequestsSnapshot<WorkerHistoryItem>> future = SettableFuture.create();
        future.set(ChangeRequestsSnapshot.success(changeHistory.getLastCounter(), Lists.newArrayList(items)));
        return future;
      }
    } else {
      return changeHistory.getRequestsSince(counter);
    }
  }

  public File getCompletedTaskDir()
  {
    return new File(storageDir, "completedTasks");
  }

  private void moveFromRunningToCompleted(String taskId, TaskAnnouncement taskAnnouncement)
  {
    synchronized (lock) {
      runningTasks.remove(taskId);
      addCompletedTask(taskId, taskAnnouncement);

      try {
        FileUtils.writeAtomically(
            new File(getCompletedTaskDir(), taskId), getTmpTaskDir(),
            os -> {
              jsonMapper.writeValue(os, taskAnnouncement);
              return null;
            }
        );
      }
      catch (IOException ex) {
        log.error(ex, "Error while trying to persist completed task[%s] announcement.", taskId);
        throw new ISE("Persisting completed task[%s] announcement failed because [%s].", taskId, ex.getMessage());
      }
    }
  }

  private void initCompletedTasks() throws IOException
  {
    File completedTaskDir = getCompletedTaskDir();
    log.debug("Looking for any previously completed tasks on disk[%s].", completedTaskDir);

    FileUtils.mkdirp(completedTaskDir);

    for (File taskFile : completedTaskDir.listFiles()) {
      try {
        String taskId = taskFile.getName();
        TaskAnnouncement taskAnnouncement = jsonMapper.readValue(taskFile, TaskAnnouncement.class);
        if (taskId.equals(taskAnnouncement.getTaskId())) {
          addCompletedTask(taskId, taskAnnouncement);
        } else {
          throw new ISE("Corrupted completed task on disk[%s].", taskFile.getAbsoluteFile());
        }
      }
      catch (IOException ex) {
        log.error(ex, "Failed to read completed task from disk at [%s]. Ignored.", taskFile.getAbsoluteFile());
      }
    }

    if (!completedTasks.isEmpty()) {
      log.info(
          "Found %,d complete tasks from previous run: %s",
          completedTasks.size(),
          completedTasks.values().stream().map(
              taskAnnouncement ->
                  StringUtils.format("%s (%s)", taskAnnouncement.getTaskId(), taskAnnouncement.getStatus())
          ).collect(Collectors.joining(", "))
      );
    }
  }

  private void scheduleCompletedTasksCleanup()
  {
    completedTasksCleanupExecutor.scheduleAtFixedRate(
        () -> {
          try {
            this.doCompletedTasksCleanup();
          }
          catch (Throwable th) {
            log.error(th, "Got unknown exception while running the scheduled cleanup.");
          }
        },
        1,
        5,
        TimeUnit.MINUTES
    );
  }

  public void workerEnabled()
  {
    Preconditions.checkState(lifecycleLock.awaitStarted(1, TimeUnit.SECONDS), "not started");

    if (disabled.compareAndSet(true, false)) {
      changeHistory.addChangeRequest(new WorkerHistoryItem.Metadata(false));
    }
  }

  public void workerDisabled()
  {
    Preconditions.checkState(lifecycleLock.awaitStarted(1, TimeUnit.SECONDS), "not started");

    if (disabled.compareAndSet(false, true)) {
      changeHistory.addChangeRequest(new WorkerHistoryItem.Metadata(true));
    }
  }

  public boolean isWorkerEnabled()
  {
    Preconditions.checkState(lifecycleLock.awaitStarted(1, TimeUnit.SECONDS), "not started");
    return !disabled.get();
  }

  /**
   * Remove items from {@link #completedTasks} that the Overlord believes has completed. Scheduled by
   * {@link #scheduleCompletedTasksCleanup()}.
   */
  void doCompletedTasksCleanup() throws InterruptedException
  {
    if (completedTasks.isEmpty()) {
      log.debug("Skipping completed tasks cleanup, because there are no completed tasks.");
      return;
    }

    ImmutableSet<String> taskIds = ImmutableSet.copyOf(completedTasks.keySet());
    Either<Throwable, Map<String, TaskStatus>> apiCallResult;

    try {
      apiCallResult = Either.value(FutureUtils.get(overlordClient.taskStatuses(taskIds), true));
      log.debug("Received completed task status response [%s].", apiCallResult);
    }
    catch (ExecutionException e) {
      if (e.getCause() instanceof HttpResponseException) {
        final HttpResponseStatus status = ((HttpResponseException) e.getCause()).getResponse().getStatus();
        if (status.getCode() == 404) {
          // NOTE: this is to support backward compatibility, when overlord doesn't have "activeTasks" endpoint.
          // this if clause should be removed in a future release.
          log.debug("Deleting all completed tasks. Overlord appears to be running on older version.");
          apiCallResult = Either.value(ImmutableMap.of());
        } else {
          apiCallResult = Either.error(e.getCause());
        }
      } else {
        apiCallResult = Either.error(e.getCause());
      }
    }

    if (apiCallResult.isError()) {
      log.warn(
          apiCallResult.error(),
          "Exception while getting active tasks from Overlord. Will retry on next scheduled run."
      );

      return;
    }

    for (String taskId : taskIds) {
      TaskStatus status = apiCallResult.valueOrThrow().get(taskId);
      if (status == null || status.isComplete()) {
        log.debug(
            "Deleting completed task[%s] information, Overlord task status[%s].",
            taskId,
            status == null ? "unknown" : status.getStatusCode()
        );

        completedTasks.remove(taskId);
        File taskFile = new File(getCompletedTaskDir(), taskId);
        try {
          Files.deleteIfExists(taskFile.toPath());
          changeHistory.addChangeRequest(new WorkerHistoryItem.TaskRemoval(taskId));
        }
        catch (IOException ex) {
          log.error(ex, "Failed to delete completed task from disk [%s].", taskFile);
        }
      }
    }
  }

  /**
   * Add a completed task to {@link #completedTasks}. It will eventually be removed by
   * {@link #doCompletedTasksCleanup()}.
   */
  void addCompletedTask(final String taskId, final TaskAnnouncement taskAnnouncement)
  {
    completedTasks.put(taskId, taskAnnouncement);
  }

  private <T> Map<String, Long> getNumTasksPerDatasource(Collection<T> taskList, Function<T, String> getDataSourceFunc)
  {
    final Map<String, Long> dataSourceToTaskCount = new HashMap<>();

    for (T task : taskList) {
      dataSourceToTaskCount.merge(getDataSourceFunc.apply(task), 1L, Long::sum);
    }
    return dataSourceToTaskCount;
  }

  @Override
  public Map<String, Long> getWorkerRunningTasks()
  {
    return getNumTasksPerDatasource(CollectionUtils.mapValues(runningTasks, detail -> detail.task).values(), Task::getDataSource);
  }

  @Override
  public Map<String, Long> getWorkerAssignedTasks()
  {
    return getNumTasksPerDatasource(assignedTasks.values(), Task::getDataSource);
  }

  @Override
  public Map<String, Long> getWorkerCompletedTasks()
  {
    return getNumTasksPerDatasource(this.getCompletedTasks().values(), TaskAnnouncement::getTaskDataSource);
  }

  @Override
  public Map<String, Long> getWorkerFailedTasks()
  {
    return getNumTasksPerDatasource(completedTasks.entrySet().stream()
            .filter(entry -> entry.getValue().getTaskStatus().isFailure())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)).values(), TaskAnnouncement::getTaskDataSource);
  }

  @Override
  public Map<String, Long> getWorkerSuccessfulTasks()
  {
    return getNumTasksPerDatasource(completedTasks.entrySet().stream()
            .filter(entry -> entry.getValue().getTaskStatus().isSuccess())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)).values(), TaskAnnouncement::getTaskDataSource);
  }

  private static class TaskDetails
  {
    private final Task task;
    private final long startTime;
    private TaskStatus status;
    private TaskLocation location;

    public TaskDetails(Task task)
    {
      this.task = task;
      this.startTime = System.currentTimeMillis();
      this.status = TaskStatus.running(task.getId());
      this.location = TaskLocation.unknown();
    }
  }

  private interface Notice
  {
    String getTaskId();

    void handle();
  }

  private class RunNotice implements Notice
  {
    private final Task task;

    public RunNotice(Task task)
    {
      this.task = task;
    }

    @Override
    public String getTaskId()
    {
      return task.getId();
    }

    @Override
    public void handle()
    {
      TaskAnnouncement announcement;
      synchronized (lock) {
        if (runningTasks.containsKey(task.getId()) || completedTasks.containsKey(task.getId())) {
          log.warn(
              "Got run notice for task [%s] that I am already running or completed...",
              task.getId()
          );

          taskStarted(task.getId());
          return;
        }

        final ListenableFuture<TaskStatus> future = taskRunner.run(task);
        addRunningTask(task, future);

        announcement = TaskAnnouncement.create(
            task,
            TaskStatus.running(task.getId()),
            TaskLocation.unknown()
        );

        changeHistory.addChangeRequest(new WorkerHistoryItem.TaskUpdate(announcement));

        cleanupAssignedTask(task);
        log.info("Task[%s] started.", task.getId());
      }

      taskAnnouncementChanged(announcement);
      taskStarted(task.getId());
    }
  }

  private class StatusNotice implements Notice
  {
    private final Task task;
    private final TaskStatus status;

    public StatusNotice(Task task, TaskStatus status)
    {
      this.task = task;
      this.status = status;
    }

    @Override
    public String getTaskId()
    {
      return task.getId();
    }

    @Override
    public void handle()
    {
      synchronized (lock) {
        final TaskDetails details = runningTasks.get(task.getId());

        if (details == null) {
          log.warn("Got status notice for task [%s] that isn't running...", task.getId());
          return;
        }

        if (!status.isComplete()) {
          log.warn(
              "Got status notice for task [%s] that isn't complete (status = [%s])...",
              task.getId(),
              status.getStatusCode()
          );
          return;
        }

        details.status = status.withDuration(System.currentTimeMillis() - details.startTime);

        TaskAnnouncement latest = TaskAnnouncement.create(
            details.task,
            details.status,
            details.location
        );

        moveFromRunningToCompleted(task.getId(), latest);

        changeHistory.addChangeRequest(new WorkerHistoryItem.TaskUpdate(latest));
        taskAnnouncementChanged(latest);
        log.info(
            "Task [%s] completed with status [%s].",
            task.getId(),
            status.getStatusCode()
        );
      }
    }
  }

  private class LocationNotice implements Notice
  {
    private final String taskId;
    private final TaskLocation location;

    public LocationNotice(String taskId, TaskLocation location)
    {
      this.taskId = taskId;
      this.location = location;
    }

    @Override
    public String getTaskId()
    {
      return taskId;
    }

    @Override
    public void handle()
    {
      synchronized (lock) {
        final TaskDetails details = runningTasks.get(taskId);

        if (details == null) {
          log.warn("Got location notice for task [%s] that isn't running...", taskId);
          return;
        }

        if (!Objects.equals(details.location, location)) {
          details.location = location;

          TaskAnnouncement latest = TaskAnnouncement.create(
              details.task,
              details.status,
              details.location
          );

          changeHistory.addChangeRequest(new WorkerHistoryItem.TaskUpdate(latest));
          taskAnnouncementChanged(latest);
        }
      }
    }
  }

  // ZK_CLEANUP_TODO :
  //Note: Following abstract methods exist only to support WorkerTaskMonitor that
  //watches task assignments and updates task statuses inside Zookeeper. When the transition to HTTP is complete
  //in Overlord as well as MiddleManagers then WorkerTaskMonitor should be deleted, this class should no longer be abstract
  //and the methods below should be removed.
  protected void taskStarted(String taskId)
  {

  }

  protected void taskAnnouncementChanged(TaskAnnouncement announcement)
  {

  }
}
