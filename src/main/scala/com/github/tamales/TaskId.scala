package com.github.tamales

import java.net.URI

case class TaskId(self:URI) {
  def this(self:String) = this(URI.create(self))
  override def toString: String = self.getHost+self.getPath
}
