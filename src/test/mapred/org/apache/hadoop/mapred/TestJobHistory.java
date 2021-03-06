/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapred;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import junit.framework.TestCase;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.mapred.JobHistory.*;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Tests the JobHistory files - to catch any changes to JobHistory that can
 * cause issues for the execution of JobTracker.RecoveryManager, HistoryViewer.
 *
 * testJobHistoryFile
 * Run a job that will be succeeded and validate its history file format and
 * content.
 *
 * testJobHistoryUserLogLocation
 * Run jobs with the given values of hadoop.job.history.user.location as
 *   (1)null(default case), (2)"none", and (3)some user specified dir.
 *   Validate user history file location in each case.
 *
 * testJobHistoryJobStatus
 * Run jobs that will be (1) succeeded (2) failed (3) killed.
 *   Validate job status read from history file in each case.
 *
 * Future changes to job history are to be reflected here in this file.
 */
public class TestJobHistory extends TestCase {
   private static final Log LOG = LogFactory.getLog(TestJobHistory.class);
 
  private static String TEST_ROOT_DIR = new File(System.getProperty(
      "test.build.data", "/tmp")).toURI().toString().replace(' ', '+');

  private static final Pattern digitsPattern =
                                     Pattern.compile(JobHistory.DIGITS);

  // hostname like   /default-rack/host1.foo.com OR host1.foo.com
  private static final Pattern hostNamePattern = Pattern.compile(
                                       "(/(([\\w\\-\\.]+)/)+)?([\\w\\-\\.]+)");

  private static final String IP_ADDR =
                       "\\d\\d?\\d?\\.\\d\\d?\\d?\\.\\d\\d?\\d?\\.\\d\\d?\\d?";

  // hostname like   /default-rack/host1.foo.com OR host1.foo.com
  private static final Pattern trackerNamePattern = Pattern.compile(
                         "tracker_" + hostNamePattern + ":([\\w\\-\\.]+)/" +
                         IP_ADDR + ":" + JobHistory.DIGITS);

  private static final Pattern splitsPattern = Pattern.compile(
                              hostNamePattern + "(," + hostNamePattern + ")*");

  private static Map<String, List<String>> taskIDsToAttemptIDs =
                                     new HashMap<String, List<String>>();

  //Each Task End seen from history file is added here
  private static List<String> taskEnds = new ArrayList<String>();

  // List of tasks that appear in history file after JT reatart. This is to
  // allow START_TIME=0 for these tasks.
  private static List<String> ignoreStartTimeOfTasks = new ArrayList<String>();

  // List of potential tasks whose start time can be 0 because of JT restart
  private static List<String> tempIgnoreStartTimeOfTasks = new ArrayList<String>();

  /**
   * Listener for history log file, it populates JobHistory.JobInfo
   * object with data from log file and validates the data.
   */
  static class TestListener
                    extends DefaultJobHistoryParser.JobTasksParseListener {
    int lineNum;//line number of history log file
    boolean isJobLaunched;
    boolean isJTRestarted;

    TestListener(JobInfo job) {
      super(job);
      lineNum = 0;
      isJobLaunched = false;
      isJTRestarted = false;
    }

    // TestListener implementation
    public void handle(RecordTypes recType, Map<Keys, String> values)
    throws IOException {

      lineNum++;

      // Check if the record is of type Meta
      if (recType == JobHistory.RecordTypes.Meta) {
        long version = Long.parseLong(values.get(Keys.VERSION));
        assertTrue("Unexpected job history version ",
                   (version >= 0 && version <= JobHistory.VERSION));
      }
      else if (recType.equals(RecordTypes.Job)) {
        String jobid = values.get(Keys.JOBID);
        assertTrue("record type 'Job' is seen without JOBID key" +
        		" in history file at line " + lineNum, jobid != null);
        JobID id = JobID.forName(jobid);
        assertTrue("JobID in history file is in unexpected format " +
                  "at line " + lineNum, id != null);
        String time = values.get(Keys.LAUNCH_TIME);
        if (time != null) {
          if (isJobLaunched) {
            // We assume that if we see LAUNCH_TIME again, it is because of JT restart
            isJTRestarted = true;
          }
          else {// job launched first time
            isJobLaunched = true;
          }
        }
        time = values.get(Keys.FINISH_TIME);
        if (time != null) {
          assertTrue ("Job FINISH_TIME is seen in history file at line " +
                      lineNum + " before LAUNCH_TIME is seen", isJobLaunched);
        }
      }
      else if (recType.equals(RecordTypes.Task)) {
        String taskid = values.get(Keys.TASKID);
        assertTrue("record type 'Task' is seen without TASKID key" +
        		" in history file at line " + lineNum, taskid != null);
        TaskID id = TaskID.forName(taskid);
        assertTrue("TaskID in history file is in unexpected format " +
                  "at line " + lineNum, id != null);
        
        String time = values.get(Keys.START_TIME);
        if (time != null) {
          List<String> attemptIDs = taskIDsToAttemptIDs.get(taskid);
          assertTrue("Duplicate START_TIME seen for task " + taskid +
                     " in history file at line " + lineNum, attemptIDs == null);
          attemptIDs = new ArrayList<String>();
          taskIDsToAttemptIDs.put(taskid, attemptIDs);

          if (isJTRestarted) {
            // This maintains a potential ignoreStartTimeTasks list
            tempIgnoreStartTimeOfTasks.add(taskid);
          }
        }

        time = values.get(Keys.FINISH_TIME);
        if (time != null) {
          String s = values.get(Keys.TASK_STATUS);
          if (s != null) {
            List<String> attemptIDs = taskIDsToAttemptIDs.get(taskid);
            assertTrue ("Task FINISH_TIME is seen in history file at line " +
                    lineNum + " before START_TIME is seen", attemptIDs != null);

            // Check if all the attemptIDs of this task are finished
            assertTrue("TaskId " + taskid + " is finished at line " +
                       lineNum + " but its attemptID is not finished.",
                       (attemptIDs.size() <= 1));

            // Check if at least 1 attempt of this task is seen
            assertTrue("TaskId " + taskid + " is finished at line " +
                       lineNum + " but no attemptID is seen before this.",
                       attemptIDs.size() == 1);

            if (s.equals("KILLED") || s.equals("FAILED")) {
              // Task End with KILLED/FAILED status in history file is
              // considered as TaskEnd, TaskStart. This is useful in checking
              // the order of history lines.
              attemptIDs = new ArrayList<String>();
              taskIDsToAttemptIDs.put(taskid, attemptIDs);
            }
            else {
              taskEnds.add(taskid);
            }
          }
          else {
            // This line of history file could be just an update to finish time
          }
        }
      }
      else if (recType.equals(RecordTypes.MapAttempt) ||
                 recType.equals(RecordTypes.ReduceAttempt)) {
        String taskid =  values.get(Keys.TASKID);
        assertTrue("record type " + recType + " is seen without TASKID key" +
        		" in history file at line " + lineNum, taskid != null);
        
        String attemptId = values.get(Keys.TASK_ATTEMPT_ID);
        TaskAttemptID id = TaskAttemptID.forName(attemptId);
        assertTrue("AttemptID in history file is in unexpected format " +
                   "at line " + lineNum, id != null);
        
        String time = values.get(Keys.START_TIME);
        if (time != null) {
          List<String> attemptIDs = taskIDsToAttemptIDs.get(taskid);
          assertTrue ("TaskAttempt is seen in history file at line " + lineNum +
                      " before Task is seen", attemptIDs != null);
          assertFalse ("Duplicate TaskAttempt START_TIME is seen in history " +
                      "file at line " + lineNum, attemptIDs.remove(attemptId));

          if (attemptIDs.isEmpty()) {
            //just a boolean whether any attempt is seen or not
            attemptIDs.add("firstAttemptIsSeen");
          }
          attemptIDs.add(attemptId);

          if (tempIgnoreStartTimeOfTasks.contains(taskid) &&
              (id.getId() < 1000)) {
            // If Task line of this attempt is seen in history file after
            // JT restart and if this attempt is < 1000(i.e. attempt is noti
            // started after JT restart) - assuming single JT restart happened
            ignoreStartTimeOfTasks.add(taskid);
          }
        }

        time = values.get(Keys.FINISH_TIME);
        if (time != null) {
          List<String> attemptIDs = taskIDsToAttemptIDs.get(taskid);
          assertTrue ("TaskAttempt FINISH_TIME is seen in history file at line "
                      + lineNum + " before Task is seen", attemptIDs != null);

          assertTrue ("TaskAttempt FINISH_TIME is seen in history file at line "
                      + lineNum + " before TaskAttempt START_TIME is seen",
                      attemptIDs.remove(attemptId));
        }
      }
      super.handle(recType, values);
    }
  }

  // Check if the time is in the expected format
  private static boolean isTimeValid(String time) {
    Matcher m = digitsPattern.matcher(time);
    return m.matches() && (Long.parseLong(time) > 0);
  }

  private static boolean areTimesInOrder(String time1, String time2) {
    return (Long.parseLong(time1) <= Long.parseLong(time2));
  }

  // Validate Format of Job Level Keys, Values read from history file
  private static void validateJobLevelKeyValuesFormat(Map<Keys, String> values,
                                                      String status) {
    String time = values.get(Keys.SUBMIT_TIME);
    assertTrue("Job SUBMIT_TIME is in unexpected format:" + time +
               " in history file", isTimeValid(time));

    time = values.get(Keys.LAUNCH_TIME);
    assertTrue("Job LAUNCH_TIME is in unexpected format:" + time +
               " in history file", isTimeValid(time));

    String time1 = values.get(Keys.FINISH_TIME);
    assertTrue("Job FINISH_TIME is in unexpected format:" + time1 +
               " in history file", isTimeValid(time1));
    assertTrue("Job FINISH_TIME is < LAUNCH_TIME in history file",
               areTimesInOrder(time, time1));

    String stat = values.get(Keys.JOB_STATUS);
    assertTrue("Unexpected JOB_STATUS \"" + stat + "\" is seen in" +
               " history file", (status.equals(stat)));

    String priority = values.get(Keys.JOB_PRIORITY);
    assertTrue("Unknown priority for the job in history file",
               (priority.equals("HIGH") ||
                priority.equals("LOW")  || priority.equals("NORMAL") ||
                priority.equals("VERY_HIGH") || priority.equals("VERY_LOW")));
  }

  // Validate Format of Task Level Keys, Values read from history file
  private static void validateTaskLevelKeyValuesFormat(JobInfo job,
                                  boolean splitsCanBeEmpty) {
    Map<String, JobHistory.Task> tasks = job.getAllTasks();

    // validate info of each task
    for (JobHistory.Task task : tasks.values()) {

      String tid = task.get(Keys.TASKID);
      String time = task.get(Keys.START_TIME);
      // We allow START_TIME=0 for tasks seen in history after JT restart
      if (!ignoreStartTimeOfTasks.contains(tid) || (Long.parseLong(time) != 0)) {
        assertTrue("Task START_TIME of " + tid + " is in unexpected format:" +
                 time + " in history file", isTimeValid(time));
      }

      String time1 = task.get(Keys.FINISH_TIME);
      assertTrue("Task FINISH_TIME of " + tid + " is in unexpected format:" +
                 time1 + " in history file", isTimeValid(time1));
      assertTrue("Task FINISH_TIME is < START_TIME in history file",
                 areTimesInOrder(time, time1));

      // Make sure that the Task type exists and it is valid
      String type = task.get(Keys.TASK_TYPE);
      assertTrue("Unknown Task type \"" + type + "\" is seen in " +
                 "history file for task " + tid,
                 (type.equals("MAP") || type.equals("REDUCE") ||
                  type.equals("SETUP") || type.equals("CLEANUP")));

      if (type.equals("MAP")) {
        String splits = task.get(Keys.SPLITS);
        //order in the condition OR check is important here
        if (!splitsCanBeEmpty || splits.length() != 0) {
          Matcher m = splitsPattern.matcher(splits);
          assertTrue("Unexpected format of SPLITS \"" + splits + "\" is seen" +
                     " in history file for task " + tid, m.matches());
        }
      }

      // Validate task status
      String status = task.get(Keys.TASK_STATUS);
      assertTrue("Unexpected TASK_STATUS \"" + status + "\" is seen in" +
                 " history file for task " + tid, (status.equals("SUCCESS") ||
                 status.equals("FAILED") || status.equals("KILLED")));
    }
  }

  // Validate foramt of Task Attempt Level Keys, Values read from history file
  private static void validateTaskAttemptLevelKeyValuesFormat(JobInfo job) {
    Map<String, JobHistory.Task> tasks = job.getAllTasks();

    // For each task
    for (JobHistory.Task task : tasks.values()) {
      // validate info of each attempt
      for (JobHistory.TaskAttempt attempt : task.getTaskAttempts().values()) {

        String id = attempt.get(Keys.TASK_ATTEMPT_ID);
        String time = attempt.get(Keys.START_TIME);
        assertTrue("START_TIME of task attempt " + id +
                   " is in unexpected format:" + time +
                   " in history file", isTimeValid(time));

        String time1 = attempt.get(Keys.FINISH_TIME);
        assertTrue("FINISH_TIME of task attempt " + id +
                   " is in unexpected format:" + time1 +
                   " in history file", isTimeValid(time1));
        assertTrue("Task FINISH_TIME is < START_TIME in history file",
                   areTimesInOrder(time, time1));

        // Make sure that the Task type exists and it is valid
        String type = attempt.get(Keys.TASK_TYPE);
        assertTrue("Unknown Task type \"" + type + "\" is seen in " +
                   "history file for task attempt " + id,
                   (type.equals("MAP") || type.equals("REDUCE") ||
                    type.equals("SETUP") || type.equals("CLEANUP")));

        // Validate task status
        String status = attempt.get(Keys.TASK_STATUS);
        assertTrue("Unexpected TASK_STATUS \"" + status + "\" is seen in" +
                   " history file for task attempt " + id,
                   (status.equals("SUCCESS") || status.equals("FAILED") ||
                    status.equals("KILLED")));

        // Successful Reduce Task Attempts should have valid SHUFFLE_FINISHED
        // time and SORT_FINISHED time
        if (type.equals("REDUCE") && status.equals("SUCCESS")) {
          time1 = attempt.get(Keys.SHUFFLE_FINISHED);
          assertTrue("SHUFFLE_FINISHED time of task attempt " + id +
                     " is in unexpected format:" + time1 +
                     " in history file", isTimeValid(time1));
          assertTrue("Reduce Task SHUFFLE_FINISHED time is < START_TIME " +
                     "in history file", areTimesInOrder(time, time1));
          time = attempt.get(Keys.SORT_FINISHED);
          assertTrue("SORT_FINISHED of task attempt " + id +
                     " is in unexpected format:" + time +
                     " in history file", isTimeValid(time));
          assertTrue("Reduce Task SORT_FINISHED time is < SORT_FINISHED time" +
                     " in history file", areTimesInOrder(time1, time));
        }
        else if (type.equals("MAP") && status.equals("SUCCESS")) {
          // Successful MAP Task Attempts should have valid MAP_FINISHED time
          time1 = attempt.get(Keys.MAP_FINISHED);
          assertTrue("MAP_FINISHED time of task attempt " + id +
                     " is in unexpected format:" + time1 +
                     " in history file", isTimeValid(time1));
          assertTrue("MAP_FINISHED time of map task is < START_TIME " +
                     "in history file", areTimesInOrder(time, time1));
        }

        // check if hostname is valid
        String hostname = attempt.get(Keys.HOSTNAME);
        Matcher m = hostNamePattern.matcher(hostname);
        assertTrue("Unexpected Host name of task attempt " + id, m.matches());

        // check if trackername is valid
        String trackerName = attempt.get(Keys.TRACKER_NAME);
        m = trackerNamePattern.matcher(trackerName);
        assertTrue("Unexpected tracker name of task attempt " + id,
                   m.matches());

        if (!status.equals("KILLED")) {
          // check if http port is valid
          String httpPort = attempt.get(Keys.HTTP_PORT);
          m = digitsPattern.matcher(httpPort);
          assertTrue("Unexpected http port of task attempt " + id, m.matches());
        }
        
        // check if counters are parsable
        String counters = attempt.get(Keys.COUNTERS);
        try {
          Counters readCounters = Counters.fromEscapedCompactString(counters);
          assertTrue("Counters of task attempt " + id + " are not parsable",
                     readCounters != null);
        } catch (ParseException pe) {
          LOG.warn("While trying to parse counters of task attempt " + id +
                   ", " + pe);
        }
      }
    }
  }

  /**
   *  Validates the format of contents of history file
   *  (1) history file exists and in correct location
   *  (2) Verify if the history file is parsable
   *  (3) Validate the contents of history file
   *     (a) Format of all TIMEs are checked against a regex
   *     (b) validate legality/format of job level key, values
   *     (c) validate legality/format of task level key, values
   *     (d) validate legality/format of attempt level key, values
   *     (e) check if all the TaskAttempts, Tasks started are finished.
   *         Check finish of each TaskAttemptID against its start to make sure
   *         that all TaskAttempts, Tasks started are indeed finished and the
   *         history log lines are in the proper order.
   *         We want to catch ordering of history lines like
   *            Task START
   *            Attempt START
   *            Task FINISH
   *            Attempt FINISH
   *         (speculative execution is turned off for this).
   * @param id job id
   * @param conf job conf
   */
  static void validateJobHistoryFileFormat(JobID id, JobConf conf,
                 String status, boolean splitsCanBeEmpty) throws IOException  {

    // Get the history file name
    String logFileName = JobHistory.JobInfo.getJobHistoryFileName(conf, id);

    // Framework history log file location
    Path logFile = JobHistory.JobInfo.getJobHistoryLogLocation(logFileName);
    FileSystem fileSys = logFile.getFileSystem(conf);
 
    // Check if the history file exists
    assertTrue("History file does not exist", fileSys.exists(logFile));


    // check if the history file is parsable
    String[] jobDetails = JobHistory.JobInfo.decodeJobHistoryFileName(
    		                                   logFileName).split("_");

    String jobId = jobDetails[2] + "_" + jobDetails[3] + "_" + jobDetails[4];
    JobHistory.JobInfo jobInfo = new JobHistory.JobInfo(jobId);

    TestListener l = new TestListener(jobInfo);
    JobHistory.parseHistoryFromFS(logFile.toUri().getPath(), l, fileSys);


    // validate format of job level key, values
    validateJobLevelKeyValuesFormat(jobInfo.getValues(), status);

    // validate format of task level key, values
    validateTaskLevelKeyValuesFormat(jobInfo, splitsCanBeEmpty);

    // validate format of attempt level key, values
    validateTaskAttemptLevelKeyValuesFormat(jobInfo);

    // check if all the TaskAttempts, Tasks started are finished for
    // successful jobs
    if (status.equals("SUCCESS")) {
      // Make sure that the lists in taskIDsToAttemptIDs are empty.
      for(Iterator<String> it = taskIDsToAttemptIDs.keySet().iterator();it.hasNext();) {
        String taskid = it.next();
        assertTrue("There are some Tasks which are not finished in history " +
                   "file.", taskEnds.contains(taskid));
        List<String> attemptIDs = taskIDsToAttemptIDs.get(taskid);
        if(attemptIDs != null) {
          assertTrue("Unexpected. TaskID " + taskid + " has task attempt(s)" +
                     " that are not finished.", (attemptIDs.size() == 1));
        }
      }
    }
  }

  // Validate Job Level Keys, Values read from history file by
  // comparing them with the actual values from JT.
  private static void validateJobLevelKeyValues(MiniMRCluster mr,
          RunningJob job, JobInfo jobInfo, JobConf conf) throws IOException  {

    JobTracker jt = mr.getJobTrackerRunner().getJobTracker();
    JobInProgress jip = jt.getJob(job.getID());

    Map<Keys, String> values = jobInfo.getValues();

    assertTrue("SUBMIT_TIME of job obtained from history file did not " +
               "match the expected value", jip.getStartTime() ==
               Long.parseLong(values.get(Keys.SUBMIT_TIME)));

    assertTrue("LAUNCH_TIME of job obtained from history file did not " +
               "match the expected value", jip.getLaunchTime() ==
               Long.parseLong(values.get(Keys.LAUNCH_TIME)));

    assertTrue("FINISH_TIME of job obtained from history file did not " +
               "match the expected value", jip.getFinishTime() ==
               Long.parseLong(values.get(Keys.FINISH_TIME)));

    assertTrue("Job Status of job obtained from history file did not " +
               "match the expected value",
               values.get(Keys.JOB_STATUS).equals("SUCCESS"));

    assertTrue("Job Priority of job obtained from history file did not " +
               "match the expected value", jip.getPriority().toString().equals(
               values.get(Keys.JOB_PRIORITY)));

    assertTrue("Job Name of job obtained from history file did not " +
               "match the expected value", JobInfo.getJobName(conf).equals(
               values.get(Keys.JOBNAME)));

    assertTrue("User Name of job obtained from history file did not " +
               "match the expected value", JobInfo.getUserName(conf).equals(
               values.get(Keys.USER)));

    // Validate job counters
    Counters c = jip.getCounters();
    assertTrue("Counters of job obtained from history file did not " +
               "match the expected value",
               c.makeEscapedCompactString().equals(values.get(Keys.COUNTERS)));

    // Validate number of total maps, total reduces, finished maps,
    // finished reduces, failed maps, failed recudes
    String totalMaps = values.get(Keys.TOTAL_MAPS);
    assertTrue("Unexpected number of total maps in history file",
               Integer.parseInt(totalMaps) == jip.desiredMaps());

    String totalReduces = values.get(Keys.TOTAL_REDUCES);
    assertTrue("Unexpected number of total reduces in history file",
               Integer.parseInt(totalReduces) == jip.desiredReduces());

    String finMaps = values.get(Keys.FINISHED_MAPS);
    assertTrue("Unexpected number of finished maps in history file",
               Integer.parseInt(finMaps) == jip.finishedMaps());

    String finReduces = values.get(Keys.FINISHED_REDUCES);
    assertTrue("Unexpected number of finished reduces in history file",
               Integer.parseInt(finReduces) == jip.finishedReduces());

    String failedMaps = values.get(Keys.FAILED_MAPS);
    assertTrue("Unexpected number of failed maps in history file",
               Integer.parseInt(failedMaps) == jip.failedMapTasks);

    String failedReduces = values.get(Keys.FAILED_REDUCES);
    assertTrue("Unexpected number of failed reduces in history file",
               Integer.parseInt(failedReduces) == jip.failedReduceTasks);
  }

  // Validate Task Level Keys, Values read from history file by
  // comparing them with the actual values from JT.
  private static void validateTaskLevelKeyValues(MiniMRCluster mr,
                      RunningJob job, JobInfo jobInfo) throws IOException  {

    JobTracker jt = mr.getJobTrackerRunner().getJobTracker();
    JobInProgress jip = jt.getJob(job.getID());

    // Get the 1st map, 1st reduce, cleanup & setup taskIDs and
    // validate their history info
    TaskID mapTaskId = new TaskID(job.getID(), TaskType.MAP, 0);
    TaskID reduceTaskId = new TaskID(job.getID(), TaskType.REDUCE, 0);

    TaskInProgress cleanups[] = jip.getCleanupTasks();
    TaskID cleanupTaskId;
    if (cleanups[0].isComplete()) {
      cleanupTaskId = cleanups[0].getTIPId();
    }
    else {
      cleanupTaskId = cleanups[1].getTIPId();
    }

    TaskInProgress setups[] = jip.getSetupTasks();
    TaskID setupTaskId;
    if (setups[0].isComplete()) {
      setupTaskId = setups[0].getTIPId();
    }
    else {
      setupTaskId = setups[1].getTIPId();
    }

    Map<String, JobHistory.Task> tasks = jobInfo.getAllTasks();

    // validate info of the 4 tasks(cleanup, setup, 1st map, 1st reduce)
    for (JobHistory.Task task : tasks.values()) {

      String tid = task.get(Keys.TASKID);
      if (tid.equals(mapTaskId.toString()) ||
          tid.equals(reduceTaskId.toString()) ||
          tid.equals(cleanupTaskId.toString()) ||
          tid.equals(setupTaskId.toString())) {

        TaskID taskId = null;
        if (tid.equals(mapTaskId.toString())) {
          taskId = mapTaskId;
        }
        else if (tid.equals(reduceTaskId.toString())) {
          taskId = reduceTaskId;
        }
        else if (tid.equals(cleanupTaskId.toString())) {
          taskId = cleanupTaskId;
        }
        else if (tid.equals(setupTaskId.toString())) {
          taskId = setupTaskId;
        }
        TaskInProgress tip = jip.getTaskInProgress(taskId);
        assertTrue("START_TIME of Task " + tid + " obtained from history " +
             "file did not match the expected value", tip.getExecStartTime() ==
             Long.parseLong(task.get(Keys.START_TIME)));

        assertTrue("FINISH_TIME of Task " + tid + " obtained from history " +
             "file did not match the expected value", tip.getExecFinishTime() ==
             Long.parseLong(task.get(Keys.FINISH_TIME)));

        if (taskId == mapTaskId) {//check splits only for map task
          assertTrue("Splits of Task " + tid + " obtained from history file " +
                     " did not match the expected value",
                     tip.getSplitNodes().equals(task.get(Keys.SPLITS)));
        }

        TaskAttemptID attemptId = tip.getSuccessfulTaskid();
        TaskStatus ts = tip.getTaskStatus(attemptId);

        // Validate task counters
        Counters c = ts.getCounters();
        assertTrue("Counters of Task " + tid + " obtained from history file " +
                   " did not match the expected value",
                  c.makeEscapedCompactString().equals(task.get(Keys.COUNTERS)));
      }
    }
  }

  // Validate Task Attempt Level Keys, Values read from history file by
  // comparing them with the actual values from JT.
  private static void validateTaskAttemptLevelKeyValues(MiniMRCluster mr,
                      RunningJob job, JobInfo jobInfo) throws IOException  {

    JobTracker jt = mr.getJobTrackerRunner().getJobTracker();
    JobInProgress jip = jt.getJob(job.getID());

    Map<String, JobHistory.Task> tasks = jobInfo.getAllTasks();

    // For each task
    for (JobHistory.Task task : tasks.values()) {
      // validate info of each attempt
      for (JobHistory.TaskAttempt attempt : task.getTaskAttempts().values()) {

        String idStr = attempt.get(Keys.TASK_ATTEMPT_ID);
        TaskAttemptID attemptId = TaskAttemptID.forName(idStr);
        TaskID tid = attemptId.getTaskID();

        // Validate task id
        assertTrue("Task id of Task Attempt " + idStr + " obtained from " +
                   "history file did not match the expected value",
                   tid.toString().equals(attempt.get(Keys.TASKID)));

        TaskInProgress tip = jip.getTaskInProgress(tid);
        TaskStatus ts = tip.getTaskStatus(attemptId);

        // Validate task attempt start time
        assertTrue("START_TIME of Task attempt " + idStr + " obtained from " +
                   "history file did not match the expected value",
            ts.getStartTime() == Long.parseLong(attempt.get(Keys.START_TIME)));

        // Validate task attempt finish time
        assertTrue("FINISH_TIME of Task attempt " + idStr + " obtained from " +
                   "history file did not match the expected value",
            ts.getFinishTime() == Long.parseLong(attempt.get(Keys.FINISH_TIME)));


        TaskTrackerStatus ttStatus = jt.getTaskTracker(ts.getTaskTracker());

        if (ttStatus != null) {
          assertTrue("http port of task attempt " + idStr + " obtained from " +
                     "history file did not match the expected value",
                     ttStatus.getHttpPort() ==
                     Integer.parseInt(attempt.get(Keys.HTTP_PORT)));

          if (attempt.get(Keys.TASK_STATUS).equals("SUCCESS")) {
            String ttHostname = jt.getNode(ttStatus.getHost()).toString();

            // check if hostname is valid
            assertTrue("Host name of task attempt " + idStr + " obtained from" +
                       " history file did not match the expected value",
                       ttHostname.equals(attempt.get(Keys.HOSTNAME)));
          }
        }
        if (attempt.get(Keys.TASK_STATUS).equals("SUCCESS")) {
          // Validate SHUFFLE_FINISHED time and SORT_FINISHED time of
          // Reduce Task Attempts
          if (attempt.get(Keys.TASK_TYPE).equals("REDUCE")) {
            assertTrue("SHUFFLE_FINISHED time of task attempt " + idStr +
                     " obtained from history file did not match the expected" +
                     " value", ts.getShuffleFinishTime() ==
                     Long.parseLong(attempt.get(Keys.SHUFFLE_FINISHED)));
            assertTrue("SORT_FINISHED time of task attempt " + idStr +
                     " obtained from history file did not match the expected" +
                     " value", ts.getSortFinishTime() ==
                     Long.parseLong(attempt.get(Keys.SORT_FINISHED)));
          }

          //Validate task counters
          Counters c = ts.getCounters();
          assertTrue("Counters of Task Attempt " + idStr + " obtained from " +
                     "history file did not match the expected value",
               c.makeEscapedCompactString().equals(attempt.get(Keys.COUNTERS)));
        }
        
        // check if tracker name is valid
        assertTrue("Tracker name of task attempt " + idStr + " obtained from " +
                   "history file did not match the expected value",
                   ts.getTaskTracker().equals(attempt.get(Keys.TRACKER_NAME)));
      }
    }
  }

  /**
   * Checks if the history file content is as expected comparing with the
   * actual values obtained from JT.
   * Job Level, Task Level and Task Attempt Level Keys, Values are validated.
   * @param job RunningJob object of the job whose history is to be validated
   * @param conf job conf
   */
  static void validateJobHistoryFileContent(MiniMRCluster mr,
                              RunningJob job, JobConf conf) throws IOException  {

    JobID id = job.getID();
    // Get the history file name
    String logFileName = JobHistory.JobInfo.getJobHistoryFileName(conf, id);

    // Framework history log file location
    Path logFile = JobHistory.JobInfo.getJobHistoryLogLocation(logFileName);
    FileSystem fileSys = logFile.getFileSystem(conf);
 
    // Check if the history file exists
    assertTrue("History file does not exist", fileSys.exists(logFile));


    // check if the history file is parsable
    String[] jobDetails = JobHistory.JobInfo.decodeJobHistoryFileName(
    		                                   logFileName).split("_");

    String jobId = jobDetails[2] + "_" + jobDetails[3] + "_" + jobDetails[4];
    JobHistory.JobInfo jobInfo = new JobHistory.JobInfo(jobId);

    DefaultJobHistoryParser.JobTasksParseListener l =
                   new DefaultJobHistoryParser.JobTasksParseListener(jobInfo);
    JobHistory.parseHistoryFromFS(logFile.toUri().getPath(), l, fileSys);

    // Now the history file contents are available in jobInfo. Let us compare
    // them with the actual values from JT.
    validateJobLevelKeyValues(mr, job, jobInfo, conf);
    validateTaskLevelKeyValues(mr, job, jobInfo);
    validateTaskAttemptLevelKeyValues(mr, job, jobInfo);
  }

  /** Run a job that will be succeeded and validate its history file format
   *  and its content.
   */
  public void testJobHistoryFile() throws IOException {
    MiniMRCluster mr = null;
    try {
      mr = new MiniMRCluster(2, "file:///", 3);

      // run the TCs
      JobConf conf = mr.createJobConf();

      FileSystem fs = FileSystem.get(conf);
      // clean up
      fs.delete(new Path(TEST_ROOT_DIR + "/succeed"), true);

      Path inDir = new Path(TEST_ROOT_DIR + "/succeed/input");
      Path outDir = new Path(TEST_ROOT_DIR + "/succeed/output");

      //Disable speculative execution
      conf.setSpeculativeExecution(false);

      // Make sure that the job is not removed from memory until we do finish
      // the validation of history file content
      conf.setInt("mapred.jobtracker.completeuserjobs.maximum", 10);

      // Run a job that will be succeeded and validate its history file
      RunningJob job = UtilsForTests.runJobSucceed(conf, inDir, outDir);
      validateJobHistoryFileFormat(job.getID(), conf, "SUCCESS", false);
      validateJobHistoryFileContent(mr, job, conf);

    } finally {
      if (mr != null) {
        mr.shutdown();
      }
    }
  }

  // Returns the output path where user history log file is written to with
  // default configuration setting for hadoop.job.history.user.location
  private static Path getLogLocationInOutputPath(String logFileName,
                                                      JobConf conf) {
    JobConf jobConf = new JobConf(true);//default JobConf
    FileOutputFormat.setOutputPath(jobConf,
                     FileOutputFormat.getOutputPath(conf));
    return JobHistory.JobInfo.getJobHistoryLogLocationForUser(
                                             logFileName, jobConf);
  }

  /**
   * Checks if the user history file exists in the correct dir
   * @param id job id
   * @param conf job conf
   */
  private static void validateJobHistoryUserLogLocation(JobID id, JobConf conf) 
          throws IOException  {
    // Get the history file name
    String logFileName = JobHistory.JobInfo.getJobHistoryFileName(conf, id);

    // User history log file location
    Path logFile = JobHistory.JobInfo.getJobHistoryLogLocationForUser(
                                                     logFileName, conf);
    if(logFile == null) {
      // get the output path where history file is written to when
      // hadoop.job.history.user.location is not set
      logFile = getLogLocationInOutputPath(logFileName, conf);
    }
    FileSystem fileSys = null;
    fileSys = logFile.getFileSystem(conf);

    // Check if the user history file exists in the correct dir
    if (conf.get("hadoop.job.history.user.location") == null) {
      assertTrue("User log file " + logFile + " does not exist",
                 fileSys.exists(logFile));
    }
    else if (conf.get("hadoop.job.history.user.location") == "none") {
      // history file should not exist in the output path
      assertFalse("Unexpected. User log file exists in output dir when " +
                 "hadoop.job.history.user.location is set to \"none\"",
                 fileSys.exists(logFile));
    }
    else {
      //hadoop.job.history.user.location is set to a specific location.
      // User log file should exist in that location
      assertTrue("User log file " + logFile + " does not exist",
                 fileSys.exists(logFile));

      // User log file should not exist in output path.

      // get the output path where history file is written to when
      // hadoop.job.history.user.location is not set
      Path logFile1 = getLogLocationInOutputPath(logFileName, conf);
      
      if (logFile != logFile1) {
        fileSys = logFile1.getFileSystem(conf);
        assertFalse("Unexpected. User log file exists in output dir when " +
              "hadoop.job.history.user.location is set to a different location",
              fileSys.exists(logFile1));
      }
    }
  }

  // Validate user history file location for the given values of
  // hadoop.job.history.user.location as
  // (1)null(default case), (2)"none", and (3)some user specified dir.
  public void testJobHistoryUserLogLocation() throws IOException {
    MiniMRCluster mr = null;
    try {
      mr = new MiniMRCluster(2, "file:///", 3);

      // run the TCs
      JobConf conf = mr.createJobConf();

      FileSystem fs = FileSystem.get(conf);
      // clean up
      fs.delete(new Path(TEST_ROOT_DIR + "/succeed"), true);

      Path inDir = new Path(TEST_ROOT_DIR + "/succeed/input1");
      Path outDir = new Path(TEST_ROOT_DIR + "/succeed/output1");

      // validate for the case of null(default)
      RunningJob job = UtilsForTests.runJobSucceed(conf, inDir, outDir);
      validateJobHistoryUserLogLocation(job.getID(), conf);

      inDir = new Path(TEST_ROOT_DIR + "/succeed/input2");
      outDir = new Path(TEST_ROOT_DIR + "/succeed/output2");
      // validate for the case of "none"
      conf.set("hadoop.job.history.user.location", "none");
      job = UtilsForTests.runJobSucceed(conf, inDir, outDir);
      validateJobHistoryUserLogLocation(job.getID(), conf);
 
      inDir = new Path(TEST_ROOT_DIR + "/succeed/input3");
      outDir = new Path(TEST_ROOT_DIR + "/succeed/output3");
      // validate for the case of any dir
      conf.set("hadoop.job.history.user.location", TEST_ROOT_DIR + "/succeed");
      job = UtilsForTests.runJobSucceed(conf, inDir, outDir);
      validateJobHistoryUserLogLocation(job.getID(), conf);

    } finally {
      if (mr != null) {
        mr.shutdown();
      }
    }
  }

  /**
   * Checks if the history file has expected job status
   * @param id job id
   * @param conf job conf
   */
  private static void validateJobHistoryJobStatus(JobID id, JobConf conf,
          String status) throws IOException  {

    // Get the history file name
    String logFileName = JobHistory.JobInfo.getJobHistoryFileName(conf, id);

    // Framework history log file location
    Path logFile = JobHistory.JobInfo.getJobHistoryLogLocation(logFileName);
    FileSystem fileSys = logFile.getFileSystem(conf);
 
    // Check if the history file exists
    assertTrue("History file does not exist", fileSys.exists(logFile));

    // check history file permission
    assertTrue("History file permissions does not match", 
    fileSys.getFileStatus(logFile).getPermission().equals(
       new FsPermission(JobHistory.HISTORY_FILE_PERMISSION)));
    
    // check if the history file is parsable
    String[] jobDetails = JobHistory.JobInfo.decodeJobHistoryFileName(
    		                                   logFileName).split("_");

    String jobId = jobDetails[2] + "_" + jobDetails[3] + "_" + jobDetails[4];
    JobHistory.JobInfo jobInfo = new JobHistory.JobInfo(jobId);

    DefaultJobHistoryParser.JobTasksParseListener l =
                  new DefaultJobHistoryParser.JobTasksParseListener(jobInfo);
    JobHistory.parseHistoryFromFS(logFile.toUri().getPath(), l, fileSys);

    assertTrue("Job Status read from job history file is not the expected" +
         " status", status.equals(jobInfo.getValues().get(Keys.JOB_STATUS)));
  }

  // run jobs that will be (1) succeeded (2) failed (3) killed
  // and validate job status read from history file in each case
  public void testJobHistoryJobStatus() throws IOException {
    MiniMRCluster mr = null;
    try {
      mr = new MiniMRCluster(2, "file:///", 3);

      // run the TCs
      JobConf conf = mr.createJobConf();

      FileSystem fs = FileSystem.get(conf);
      // clean up
      fs.delete(new Path(TEST_ROOT_DIR + "/succeedfailkilljob"), true);

      Path inDir = new Path(TEST_ROOT_DIR + "/succeedfailkilljob/input");
      Path outDir = new Path(TEST_ROOT_DIR + "/succeedfailkilljob/output");

      // Run a job that will be succeeded and validate its job status
      // existing in history file
      RunningJob job = UtilsForTests.runJobSucceed(conf, inDir, outDir);
      validateJobHistoryJobStatus(job.getID(), conf, "SUCCESS");
      long historyCleanerRanAt = JobHistory.HistoryCleaner.getLastRan();
      assertTrue(historyCleanerRanAt != 0);
      
      // Run a job that will be failed and validate its job status
      // existing in history file
      job = UtilsForTests.runJobFail(conf, inDir, outDir);
      validateJobHistoryJobStatus(job.getID(), conf, "FAILED");
      assertTrue(historyCleanerRanAt == JobHistory.HistoryCleaner.getLastRan());
      
      // Run a job that will be killed and validate its job status
      // existing in history file
      job = UtilsForTests.runJobKill(conf, inDir, outDir);
      validateJobHistoryJobStatus(job.getID(), conf, "KILLED");
      assertTrue(historyCleanerRanAt == JobHistory.HistoryCleaner.getLastRan());
      
    } finally {
      if (mr != null) {
        mr.shutdown();
      }
    }
  }
}
