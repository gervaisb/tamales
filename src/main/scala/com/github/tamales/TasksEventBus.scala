package com.github.tamales

import akka.actor.ActorRef
import akka.event.{EventBus, LookupClassification}

abstract class TaskEvent

class TasksEventBus extends EventBus with LookupClassification {
  override type Event = TaskEvent
  override type Classifier = String
  override type Subscriber = ActorRef

  def subscribe(subscriber: Subscriber):Boolean = {
    subscribe(subscriber, to="events")
  }

  override protected def classify(event: Event):Classifier="events"

  override protected def publish(event: Event, subscriber: ActorRef): Unit = {
    subscriber ! event
  }

  override protected def compareSubscribers(a: Subscriber, b: Subscriber): Int =
    a.compareTo(b)

  override protected def mapSize(): Int = 1


}
