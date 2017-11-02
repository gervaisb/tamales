package com.github.tamales

class Task(val id:TaskId, var summary:String, var done:Option[Boolean]=None) {

  def update(other:Task):Task = {
    this.summary = other.summary
    this.done = other.done
    this
  }

  override def toString = s"Task($id, $summary, .., $done)"
}
