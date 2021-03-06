package scalaz.zio.duration

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit._

class DurationSyntax(n: Long) {

  protected def asDuration(unit: TimeUnit): Duration = Duration(n, unit)

  def nanoseconds = asDuration(NANOSECONDS)
  def nanos       = nanoseconds
  def nanosecond  = nanoseconds
  def nano        = nanoseconds

  def microseconds = asDuration(MICROSECONDS)
  def micros       = microseconds
  def microsecond  = microseconds
  def micro        = microseconds

  def milliseconds = asDuration(MILLISECONDS)
  def millis       = milliseconds
  def millisecond  = milliseconds
  def milli        = milliseconds

  def seconds = asDuration(SECONDS)
  def second  = seconds

  def minutes = asDuration(MINUTES)
  def minute  = minutes

  def hours = asDuration(HOURS)
  def hour  = hours

  def days = asDuration(DAYS)
  def day  = days

}
