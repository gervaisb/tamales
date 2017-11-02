package com.github.tamales.impls

import java.io.IOException
import java.net.URI

import akka.actor.{Actor, ActorLogging, Props}
import com.github.tamales.Publisher.{TaskFound, TaskSaved}
import com.github.tamales.{ActorConfig, Task, TaskId}
import okhttp3._
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

/**
  * Trello is a publisher that create and update tasks in a given board.
  * The tasks that are done are moved to a configured 'complete' list while the
  * others are moved into the 'incomplete' configured list.
  * Each task are represented via a card with a link to the provider task URI
  */
object Trello {
  def props() = Props(new Trello())

  private type CardId = String
  private type Card = (CardId, Task)
}
class Trello extends Actor with ActorLogging with ActorConfig {
  import Trello._
  private val http = new OkHttpClient()
  private val cfg = new Cfg
  private val Backlog = cfg.incompleteListId
  private val Done = cfg.completeListId

  override def receive = {
    case TaskFound(task, emitter) =>
      createOrUpdate(task).foreach { location =>
        emitter ! TaskSaved(task, Some(location))
      }
  }

  private def createOrUpdate(task:Task):Try[URI] =
    find(task.id).fold {
      create(task)
    }{ current =>
      update(current.copy(_2 = current._2.update(task)))
    }

  private def create(task:Task):Try[URI] = {
    log.info(s"Creating task ${task.id} into Trello")
    val payload = new FormBody.Builder()
      .add("name", task.summary)
      .add("idList", listOf(task))
      .add("urlSource", task.id.self.toString)
      .build()
    val request = new Request.Builder()
      .url(pathTo("cards"))
      .post(payload)
      .build()
    execute(request).map { r =>
      val json = Json.parse(r.body().bytes())
      val url  = json \ "shortUrl"
      URI.create(url.as[String])
    }
  }

  private def update(card:Card):Try[URI] = {
    log.info(s"Updating card ${card._1} for task ${card._2.id} into Trello")
    val list: CardId = listOf(card._2)
    val value = new FormBody.Builder()
      .add("value", list)
      .build()
    val request = new Request.Builder()
      .url(pathTo(s"cards/${card._1}/idList"))
      .put(value)
      .build()
    execute(request).map { r =>
      val json = Json.parse(r.body().bytes())
      val url  = json \ "shortUrl"
      URI.create(url.as[String])
    }
  }

  private def find(id:TaskId):Option[(CardId, Task)] = {
    val query = "&card_fields=name,idList" +
      s"&idBoards=${cfg.boardId}&query=has:attachments ${id.self}"
    val request = new Request.Builder()
      .url(pathTo("search") + query)
      .build()
    execute(request) match {
      case Success(response) =>
        val cards = (Json.parse(response.body().bytes()) \ "cards").as[JsArray].value
        if ( cards.isEmpty ) {
          None
        } else {
          val card = cards(0)
          val task = new Task(id, (card \ "name").as[String], Some((card \ "idList").as[String] == Done))
          Some(((card \"id").as[String] -> task))
        }
      case Failure(e) => throw e
    }
  }

  private def execute(request:Request):Try[Response] = {
    val response = http.newCall(request).execute()
    if ( response.isSuccessful ) {
      Success(response)
    } else {
      Failure(new IOException(s"${request.method()} ${request.url()} -> Http_${response.code}"))
    }
  }

  private def pathTo(fragment: String):String = {
    s"https://trello.com/1/$fragment?key=${cfg.api.key}&token=${cfg.api.token}"
  }

  private def listOf(task: Task) = task.done.orElse(Some(false)).map {
      case true => Done
      case _ => Backlog
    }.get

  class Cfg {
    case class Api(
                    val key: String = config.getString("publishers.trello.api.key"),
                    val token: String = config.getString("publishers.trello.api.token")
                  )
    val api = Api()
    val boardId:String = config.getString("publishers.trello.boardId")
    val incompleteListId = config.getString("publishers.trello.list.incompleteId")
    val completeListId = config.getString("publishers.trello.list.completeId")
  }
}
