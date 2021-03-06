package views.html.helper

import play.api.i18n.{Lang,Messages}


object EnumToString {
  def apply[T <: java.lang.Enum[T]](value: T)(implicit lang: Lang) = {
    Messages(value.getDeclaringClass().getName().replace("$", ".") + "." + value.toString())
  }
}
