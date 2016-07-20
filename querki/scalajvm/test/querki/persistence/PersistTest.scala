package querki.persistence

import java.io._

import com.esotericsoftware.kryo.io._

import querki.globals._

/**
 * Mix-in trait for tests of persistent classes. The main thing here is the
 * serialRoundtrip() function, which serializes the given object and then deserializes
 * and returns it, so that you can check that that went smoothly.
 * 
 * It is *strongly* recommended that *all* persistent classes get a unit test of
 * this sort. What it's really testing is that the necessary bits and bobs are
 * getting registered in the Ecology.
 */
class PersistTest(env:PersistEnv) extends EcologyMember {
  
  def ecology = env.ecology
  
  /**
   * This creates a Kryo (based on the registrations in the Ecology), writes out the given object,
   * reads it back in again and returns it. You will usually want to follow this with assertions that
   * the read object matches the written one.
   */
  def serialRoundtrip[T <: AnyRef](in:T):T = {
    // First, sanity-check that the type is marked as UseKryo:
    env.checkObj(in)
    rawRoundtrip(in)
  }
  
  /**
   * Convenience function over serialRoundtrip. You can use this iff the class you're testing has a
   * good == function. (That is, if it's a case class, which it usually is.)
   */
  def checkSerialization[T <: AnyRef](builder: => T) = {
    val orig = builder
    val copy = serialRoundtrip(orig)
    QLog.spew(s"Checking equality of $copy")
    env.checkEquality(copy, orig)
  }
  
  def rawRoundtrip[T <: AnyRef](in:T):T = {
    env.roundtrip(in)
  }
  
  /**
   * Use this for the low-level "raw" types that should never be serialized at the top level.
   * These differ from the usual case in that they *don't* have to be marked as UseKryo.
   */
  def checkRaw[T <: AnyRef](builder: => T) = {
    val orig = builder
    val copy = rawRoundtrip(orig)
    QLog.spew(s"Checking equality of $copy")
    env.checkEquality(copy, orig)
  }  
}
