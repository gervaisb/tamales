package com.github.tamales.impls

import java.io.IOException
import java.net.{URI, URL, URLEncoder}
import java.util.Base64

import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
import com.github.tamales.Provider.Refresh
import com.github.tamales.Publisher.TaskFound
import com.github.tamales.{ActorConfig, Task, TaskId, TasksEventBus}
import okhttp3.{Request, Response}
import play.api.libs.json.{JsArray, JsObject, Json}

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

object Jira {
  def props(events: TasksEventBus) = Props(new Jira(events))
}
class Jira(val events: TasksEventBus) extends Actor with ActorLogging with ActorConfig {
  private val cfg = new Cfg
  private val http = UnsafeOkHttpClientBuilder.build()

  override def receive:Receive = {
    case Refresh =>
      log.info("Refreshing tasks")
      search().foreach { task =>
        log.info(s"Found ${task.id} from Jira")
        events.publish(TaskFound(task, self))
      }
      self ! PoisonPill // No need to get event back
  }

  private def search():Seq[Task] = {
    val request = new Request.Builder()
      .header("Authorization", basic(cfg.username, cfg.password))
      .header("Cache-Control", "no-cache")
      .header("Pragma", "no-cache")
      .header("Accept", "application/json")
      .url(pathTo("rest/api/2/search", jql="assignee = currentUser() AND status IN (Started, Accepted, \"In Progress\", Assigned)", fields="summary"))
      .build()
    log.debug(s"GET ${request.url()}")
    execute(request).map { response =>
      val json = Json.parse(response.body().bytes()).as[JsObject]
      val issues = (json \ "issues").as[JsArray].value
      issues.map { issue =>
        val key = (issue \ "key").as[String]
        val id = TaskId(URI.create(pathTo(s"browse/$key")))
        new Task(id, (issue \ "fields" \ "summary").as[String], Some(false))
      }
    }.get
  }

  private def pathTo(resource: String, jql:String=null, fields:String=null):String = {
    val path = s"${cfg.location}/$resource"
    var params = mutable.Set.empty[String]
    if ( jql!=null ) {
      params += "jql="+URLEncoder.encode(jql, "UTF-8")
    }
    if ( fields!=null ) {
      params += "fields="+URLEncoder.encode(fields, "UTF-8")
    }
    if ( params.nonEmpty ) {
      path + params.mkString("?", "&", "")
    } else {
      path
    }
  }

  private def basic(username:String, password:String): String = {
    val creds = username.getBytes("UTF-8") ++ ":".getBytes("UTF-8") ++ password.getBytes("UTF-8")
    val hash = Base64.getEncoder.encodeToString(creds)
    s"Basic $hash"
  }

  private def execute(request:Request):Try[Response] = {
    val response = http.newCall(request).execute()
    if ( response.isSuccessful ) {
      Success(response)
    } else {
      Failure(new IOException(s"${request.method()} ${request.url()} -> Http_${response.code}"))
    }
  }

  class Cfg {
    val location:String = config.getString("providers.jira.location")
    val host:String = new URL(location).getHost
    val username:String = config.getString("providers.jira.username")
    val password:String = config.getString("providers.jira.password")
  }

}
