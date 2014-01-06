package querki.basic

import models._
import models.Thing._

import models.system.OIDs.{systemOID, RootOID}
import models.system.SystemProperty
import models.system.{Optional}
import models.system.{PlainTextType}
import models.system.{DeprecatedProp, DisplayTextProp, IsModelProp, NotInheritedProp}

import querki.conventions._
import querki.core._
import querki.ecology._
import querki.types._

import modules.Module

class BasicModule(e:Ecology, val moduleId:Short) extends Module(e) with Basic {
  import MOIDs._
  
  val DeriveName = initRequires[querki.types.DeriveName]
  val Types = initRequires[querki.types.Types]
  
  /***********************************************
   * PROPERTIES
   ***********************************************/

  /**
   * If set, this is the display name of the specified object. Whereas the primary NameProp
   * has a number of restrictions, the DisplayNameProp does not. It is used to list a Thing
   * by preference when it is set.
   */
  lazy val DisplayNameProp = new SystemProperty(DisplayNameOID, PlainTextType, Optional,
    toProps(
      setName("Display Name"),
      NotInheritedProp(true),
      Types.MinTextLengthProp(1),
      PropSummary("How to show this Thing's Name"),
      PropDetails("""Most Things in Querki have a Name. (It isn't strictly required, but strongly encouraged most
          |of the time.) In general, when we list a Thing, we show its Name. However, if you want to display
          |something *other* than its Name instead, set its Display Name Property to show in its place.
          |
          |Display Name is mainly useful when the name you would like to use includes characters that aren't
          |legal in Names, such as quotes, apostrophes, commas or other punctuation characters.
          |
          |Note that the relationship of Name and Display Name is still in some flux, and things may shift a
          |bit over time. We are thinking of putting Display Name more front-and-center, and making Name derive
          |from that instead.""".stripMargin)
      ))

  override lazy val props = Seq(
    DisplayNameProp
  )
  
  /***********************************************
   * THINGS
   ***********************************************/

  object SimpleThing extends ThingState(SimpleThingOID, systemOID, RootOID,
    toProps(
      setName("Simple-Thing"),
      IsModelProp(true),
      DisplayTextProp(Optional.QNone),
      (querki.basic.MOIDs.DisplayNameOID -> Optional.QNone),
      DeriveName.DeriveNameProp(DeriveName.DeriveInitially)))

  object Page extends ThingState(PageOID, systemOID, SimpleThingOID,
    toProps(
      setName("Simple-Page"),
      IsModelProp(true),
      DeprecatedProp(true)))

  override lazy val things = Seq(
    SimpleThing,
    Page
  )
}