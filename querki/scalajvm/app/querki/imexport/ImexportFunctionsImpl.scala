package querki.imexport

import scala.concurrent.Future

import querki.api.{AutowireApiImpl, AutowireParams}
import querki.globals._

/**
 * Implementation of the public API for export/import functionality.
 * 
 * @author jducoeur
 */
class ImexportFunctionsImpl(info:AutowireParams)(implicit e:Ecology) extends AutowireApiImpl(info, e) with ImexportFunctions  {
  lazy val Imexport = interface[Imexport]
  
  def doRoute(req:Request):Future[String] = route[ImexportFunctions](this)(req)
  
  def exportSpace():String = {
    Imexport.exportSpace(rc)(state)
  }
}