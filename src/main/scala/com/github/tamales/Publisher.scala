package com.github.tamales

import java.net.URI

import akka.actor.ActorRef

/**
  * Define the contract (commands and events) emitted by a publisher.
  */
object Publisher {

  /** Sent by a provider when a new or existing task is found.
    * It is up to the provider to create a new task or update an existing one.
    * @param task     The maybe new task
    * @param provider The task provider (used to reply with {@link TaskSaved})
    */
  final case class TaskFound(task:Task, provider: ActorRef) extends TaskEvent

  /** Sent by the publisher when a task is saved. This is a reaction to the
    * {@link TaskFound} event that is sent the the {@link TaskFound} provider
    * @param task     The task that has been saved
    * @param location An optional URI that aims to the task into the publisher
    */
  final case class TaskSaved(task: Task, location:Option[URI])
}
