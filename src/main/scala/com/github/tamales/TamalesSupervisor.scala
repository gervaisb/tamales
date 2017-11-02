package com.github.tamales

import akka.actor.{Actor, ActorLogging, Props}
import com.github.tamales.Provider.Refresh
import com.github.tamales.ProviderManager.Schedule
import com.github.tamales.impls.{Evernote, Trello}


object TamalesSupervisor {
  def props() = Props(new TamalesSupervisor)
}

class TamalesSupervisor extends Actor with ActorLogging {
  override def preStart(): Unit = log.info("Tamales application started")
  override def postStop(): Unit = log.info("Tamales application stopped")

  override def receive = Actor.emptyBehavior

  private val events = new TasksEventBus
  private val publisher = context.actorOf(Trello.props(), "publisher-trello")
  private val providers = context.actorOf(ProviderManager.props(events), "providers")

  events.subscribe(publisher)

  providers ! Schedule

}
