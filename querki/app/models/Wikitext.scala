package models

import eu.henkelmann.actuarius.{Decorator, Transformer}

case class DisplayText(val str:String) {
  override def toString() = str
}
object DisplayText {
  implicit def displayText2String(disp:DisplayText) = disp.str
}

class Wikitext(wiki:String) {
  def display = {
    val transformer = new QuerkiTransformer()
    DisplayText(transformer(internal))
  }
  /**
   * Produces the "raw" string, with minimal markup. Use this for situations where you
   * don't want to allow much Wikitext, such as display names.
   */
  def raw = {
    val transformer = new RawTransformer()
    DisplayText(transformer(internal))
  }
  /**
   * This should only be used internally, never to display to the user!
   * 
   * We do simple substitutions here, that aren't worth coding into the wikitext engine
   * itself.
   * 
   * Octal 266 is Hex 182, aka the paragraph character. Enter on the numeric keypad as
   * Alt-0182.
   */
  def internal = wiki.replace('\266', '\n')
  
  /**
   * Wikitext can be concatenated just like strings.
   */
  def +(other:Wikitext) = new Wikitext(internal + other.internal)
}

object Wikitext {
  def apply(str:String) = new Wikitext(str)
}

class QuerkiTransformer extends Transformer with Decorator {
    override def deco() = this
    // TODO: we are allowing this for the short term, until I beef up the
    // Markdown dialect enough. But soon, this will only be legal on specially-marked
    // Properties.
    override def allowVerbatimXml():Boolean = true
}

class RawTransformer extends Transformer with Decorator {
    override def deco() = this
    override def allowVerbatimXml():Boolean = false
    override def decorateParagraphOpen():String = ""
    override def decorateParagraphClose():String = ""    
}