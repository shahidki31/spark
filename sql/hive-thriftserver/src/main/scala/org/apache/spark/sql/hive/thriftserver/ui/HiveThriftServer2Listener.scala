/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive.thriftserver.ui

import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import org.apache.hive.service.server.HiveServer2
import org.apache.spark.internal.config.History
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.internal.config.Status.LIVE_ENTITY_UPDATE_PERIOD
import org.apache.spark.scheduler._
import org.apache.spark.sql.hive.thriftserver.HiveThriftServer2.ExecutionState
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.status.{ElementTrackingStore, KVUtils, LiveEntity}

/**
 * An inner sparkListener called in sc.stop to clean up the HiveThriftServer2
 */
private[thriftserver] class HiveThriftServer2Listener(
    kvstore: ElementTrackingStore,
    sparkConf: SparkConf,
    server: Option[HiveServer2],
    live: Boolean = true) extends SparkListener {

  private val sessionList = new ConcurrentHashMap[String, LiveSessionData]()
  private val executionList = new ConcurrentHashMap[String, LiveExecutionData]()

  private var appId: String = _
  private var attemptId: Option[String] = _

  def initialize(appId: String, attemptId: Option[String]): Unit = {
    if (!live && sparkConf.get(History.INCREMENTAL_PARSING_ENABLED)) {
      this.appId = appId
      this.attemptId = attemptId
      try {
        val listenerData = kvstore.read(classOf[HiveThriftserver2ListenerData], appId)
        listenerData.executionList.entrySet().asScala.foreach(x =>
          executionList.put(x.getKey, x.getValue))
        listenerData.sessionList.entrySet().asScala.foreach(x =>
          sessionList.put(x.getKey, x.getValue))
      } catch {
        case e: Exception =>

      }
    }
  }

  private val (retainedStatements: Int, retainedSessions: Int) = {
    (sparkConf.get(SQLConf.THRIFTSERVER_UI_STATEMENT_LIMIT),
      sparkConf.get(SQLConf.THRIFTSERVER_UI_SESSION_LIMIT))
  }

  // How often to update live entities. -1 means "never update" when replaying applications,
  // meaning only the last write will happen. For live applications, this avoids a few
  // operations that we can live without when rapidly processing incoming events.
  private val liveUpdatePeriodNs = if (live) sparkConf.get(LIVE_ENTITY_UPDATE_PERIOD) else -1L

  // Returns true if this listener has no live data. Exposed for tests only.
  private[thriftserver] def noLiveData(): Boolean = {
    sessionList.isEmpty && executionList.isEmpty
  }

  kvstore.addTrigger(classOf[SessionInfo], retainedSessions) { count =>
    cleanupSession(count)
  }

  kvstore.addTrigger(classOf[ExecutionInfo], retainedStatements) { count =>
    cleanupExecutions(count)
  }

  kvstore.onFlush {
    if (!live) {
      flush((entity: LiveEntity) => updateStoreWithTriggerEnabled(entity))
      if (sparkConf.get(History.INCREMENTAL_PARSING_ENABLED)) {
        kvstore.write(new HiveThriftserver2ListenerData(appId, attemptId, sessionList, executionList))
      }
    }
  }

  override def onApplicationEnd(applicationEnd: SparkListenerApplicationEnd): Unit = {
    if (live) {
      server.foreach(_.stop())
    }
  }

  override def onJobStart(jobStart: SparkListenerJobStart): Unit = {
    val properties = jobStart.properties
    if (properties != null) {
      val groupId = properties.getProperty(SparkContext.SPARK_JOB_GROUP_ID)
      if (groupId != null) {
        updateJobDetails(jobStart.jobId.toString, groupId)
        }
      }
    }

  private def updateJobDetails(jobId: String, groupId: String): Unit = {
    val execList = executionList.values().asScala.filter(_.groupId == groupId).toSeq
    if (execList.nonEmpty) {
      execList.foreach { exec =>
        exec.jobId += jobId.toString
        updateLiveStore(exec)
      }
    } else {
      // It may possible that event reordering happens, such a way that JobStart event come after
      // Execution end event (Refer SPARK-27019). To handle that situation, if occurs in
      // Thriftserver, following code will take care. Here will come only if JobStart event comes
      // after Execution End event.
      val storeExecInfo = kvstore.view(classOf[ExecutionInfo]).asScala.filter(_.groupId == groupId)
      storeExecInfo.foreach { exec =>
        val liveExec = getOrCreateExecution(exec.execId, exec.statement, exec.sessionId,
          exec.startTimestamp, exec.userName)
        liveExec.jobId += jobId.toString
        updateStoreWithTriggerEnabled(liveExec)
        executionList.remove(liveExec.execId)
      }
    }
  }

  override def onOtherEvent(event: SparkListenerEvent): Unit = {
    event match {
      case e: SparkListenerThriftServerSessionCreated => onSessionCreated(e)
      case e: SparkListenerThriftServerSessionClosed => onSessionClosed(e)
      case e: SparkListenerThriftServerOperationStart => onOperationStart(e)
      case e: SparkListenerThriftServerOperationParsed => onOperationParsed(e)
      case e: SparkListenerThriftServerOperationCanceled => onOperationCanceled(e)
      case e: SparkListenerThriftServerOperationError => onOperationError(e)
      case e: SparkListenerThriftServerOperationFinish => onOperationFinished(e)
      case e: SparkListenerThriftServerOperationClosed => onOperationClosed(e)
      case _ => // Ignore
    }
  }

  private def onSessionCreated(e: SparkListenerThriftServerSessionCreated): Unit = {
    val session = getOrCreateSession(e.sessionId, e.startTime, e.ip, e.userName)
    sessionList.put(e.sessionId, session)
    updateLiveStore(session)
  }

  private def onSessionClosed(e: SparkListenerThriftServerSessionClosed): Unit = {
    val session = sessionList.get(e.sessionId)
    session.finishTimestamp = e.finishTime
    updateStoreWithTriggerEnabled(session)
    sessionList.remove(e.sessionId)
  }

  private def onOperationStart(e: SparkListenerThriftServerOperationStart): Unit = {
    val info = getOrCreateExecution(
      e.id,
      e.statement,
      e.sessionId,
      e.startTime,
      e.userName)

    info.state = ExecutionState.STARTED
    executionList.put(e.id, info)
    sessionList.get(e.sessionId).totalExecution += 1
    executionList.get(e.id).groupId = e.groupId
    updateLiveStore(executionList.get(e.id))
    updateLiveStore(sessionList.get(e.sessionId))
  }

  private def onOperationParsed(e: SparkListenerThriftServerOperationParsed): Unit = {
    executionList.get(e.id).executePlan = e.executionPlan
    executionList.get(e.id).state = ExecutionState.COMPILED
    updateLiveStore(executionList.get(e.id))
  }

  private def onOperationCanceled(e: SparkListenerThriftServerOperationCanceled): Unit = {
    executionList.get(e.id).finishTimestamp = e.finishTime
    executionList.get(e.id).state = ExecutionState.CANCELED
    updateLiveStore(executionList.get(e.id))
  }

  private def onOperationError(e: SparkListenerThriftServerOperationError): Unit = {
    executionList.get(e.id).finishTimestamp = e.finishTime
    executionList.get(e.id).detail = e.errorMsg
    executionList.get(e.id).state = ExecutionState.FAILED
    updateLiveStore(executionList.get(e.id))
  }

  private def onOperationFinished(e: SparkListenerThriftServerOperationFinish): Unit = {
    executionList.get(e.id).finishTimestamp = e.finishTime
    executionList.get(e.id).state = ExecutionState.FINISHED
    updateLiveStore(executionList.get(e.id))
  }

  private def onOperationClosed(e: SparkListenerThriftServerOperationClosed): Unit = {
    executionList.get(e.id).closeTimestamp = e.closeTime
    executionList.get(e.id).state = ExecutionState.CLOSED
    updateStoreWithTriggerEnabled(executionList.get(e.id))
    executionList.remove(e.id)
  }

  // Update both live and history stores. Trigger is enabled by default, hence
  // it will cleanup the entity which exceeds the threshold.
  def updateStoreWithTriggerEnabled(entity: LiveEntity): Unit = {
    entity.write(kvstore, System.nanoTime(), checkTriggers = true)
  }

  // Update only live stores. If trigger is enabled, it will cleanup entity
  // which exceeds the threshold.
  def updateLiveStore(entity: LiveEntity, trigger: Boolean = false): Unit = {
    val now = System.nanoTime()
    if (live && liveUpdatePeriodNs >= 0 && now - entity.lastWriteTime > liveUpdatePeriodNs) {
      entity.write(kvstore, now, checkTriggers = trigger)
    }
  }

  /** Go through all `LiveEntity`s and use `entityFlushFunc(entity)` to flush them. */
  private def flush(entityFlushFunc: LiveEntity => Unit): Unit = {
    sessionList.values.asScala.foreach(entityFlushFunc)
    executionList.values.asScala.foreach(entityFlushFunc)

  }

  private def getOrCreateSession(
     sessionId: String,
     startTime: Long,
     ip: String,
     username: String): LiveSessionData = {
    sessionList.computeIfAbsent(sessionId,
      (_: String) => new LiveSessionData(sessionId, startTime, ip, username))
  }

  private def getOrCreateExecution(
    execId: String, statement: String,
    sessionId: String, startTimestamp: Long,
    userName: String): LiveExecutionData = {
    executionList.computeIfAbsent(execId,
      (_: String) => new LiveExecutionData(execId, statement, sessionId, startTimestamp, userName))
  }

  private def cleanupExecutions(count: Long): Unit = {
    val countToDelete = calculateNumberToRemove(count, retainedStatements)
    if (countToDelete <= 0L) {
      return
    }
    val view = kvstore.view(classOf[ExecutionInfo]).index("finishTime").first(0L)
    val toDelete = KVUtils.viewToSeq(view, countToDelete.toInt) { j =>
      j.finishTimestamp != 0
    }
    toDelete.foreach { j => kvstore.delete(j.getClass, j.execId) }
  }

  private def cleanupSession(count: Long): Unit = {
    val countToDelete = calculateNumberToRemove(count, retainedSessions)
    if (countToDelete <= 0L) {
      return
    }
    val view = kvstore.view(classOf[SessionInfo]).index("finishTime").first(0L)
    val toDelete = KVUtils.viewToSeq(view, countToDelete.toInt) { j =>
      j.finishTimestamp != 0L
    }

    toDelete.foreach { j => kvstore.delete(j.getClass, j.sessionId) }
  }

  /**
   * Remove at least (retainedSize / 10) items to reduce friction. Because tracking may be done
   * asynchronously, this method may return 0 in case enough items have been deleted already.
   */
  private def calculateNumberToRemove(dataSize: Long, retainedSize: Long): Long = {
    if (dataSize > retainedSize) {
      math.max(retainedSize / 10L, dataSize - retainedSize)
    } else {
      0L
    }
  }
}

private[thriftserver] class LiveExecutionData(
    val execId: String,
    val statement: String,
    val sessionId: String,
    val startTimestamp: Long,
    val userName: String) extends LiveEntity {

    var finishTimestamp: Long = 0L
    var closeTimestamp: Long = 0L
    var executePlan: String = ""
    var detail: String = ""
    var state: ExecutionState.Value = ExecutionState.STARTED
    val jobId: ArrayBuffer[String] = ArrayBuffer[String]()
    var groupId: String = ""

  override protected def doUpdate(): Any = {
    new ExecutionInfo(
      execId,
      statement,
      sessionId,
      startTimestamp,
      userName,
      finishTimestamp,
      closeTimestamp,
      executePlan,
      detail,
      state,
      jobId,
      groupId)
  }
}

private[thriftserver] class LiveSessionData(
    val sessionId: String,
    val startTimeStamp: Long,
    val ip: String,
    val username: String) extends LiveEntity {

  var finishTimestamp: Long = 0L
  var totalExecution: Int = 0

  override protected def doUpdate(): Any = {
    new SessionInfo(
      sessionId,
      startTimeStamp,
      ip,
      username,
      finishTimestamp,
      totalExecution)
  }
}
