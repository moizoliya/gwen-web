/*
 * Copyright 2014-2015 Brady Wood, Branko Juric
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gwen.web

import java.io.File
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.Keys
import org.openqa.selenium.OutputType
import org.openqa.selenium.TakesScreenshot
import org.openqa.selenium.TimeoutException
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebDriverException
import org.openqa.selenium.WebElement
import org.openqa.selenium.support.ui.ExpectedCondition
import org.openqa.selenium.support.ui.Select
import org.openqa.selenium.support.ui.WebDriverWait
import gwen.Predefs.Kestrel
import gwen.Predefs.RegexContext
import gwen.Settings
import gwen.dsl.Failed
import gwen.dsl.Step
import gwen.eval.EnvContext
import gwen.eval.ScopedDataStack
import gwen.eval.support.RegexSupport
import gwen.eval.support.XPathSupport
import gwen.eval.support.InterpolationSupport
import gwen.dsl.SpecType
import gwen.eval.GwenOptions
import gwen.errors._
import scala.io.Source
import scala.sys.process._
import scala.collection.JavaConverters._
import org.openqa.selenium.interactions.Actions
import gwen.eval.support.JsonPathSupport
import java.io.FileNotFoundException

/**
  * Defines the web environment context. This includes the configured selenium web
  * driver instance, feature and page scopes, and web element functions.
  *
  *  @author Branko Juric
  */
class WebEnvContext(val options: GwenOptions, val scopes: ScopedDataStack) extends EnvContext(options, scopes) 
  with WebElementLocator with DriverManager {

   Try(logger.info(s"SELENIUM_HOME = ${sys.env("SELENIUM_HOME")}"))
  
   /** Resets the current context and closes the web browser. */
  override def reset() {
    super.reset()
    close()
  }

  /** Closes the current web driver. */
  override def close() {
    quit()
    super.close()
  }
  
  /**
    * Injects and executes a javascript on the current page.
    * 
    * @param javascript the script expression to execute
    * @param params optional parameters to the script
    * @param takeScreenShot true to take screenshot after performing the function
    */
  def executeScript(javascript: String, params: Any*)(implicit takeScreenShot: Boolean = false): Any = 
    withWebDriver { webDriver => 
      webDriver.asInstanceOf[JavascriptExecutor].executeScript(javascript, params.map(_.asInstanceOf[AnyRef]) : _*) tap { result =>
        if (takeScreenShot && WebSettings.`gwen.web.capture.screenshots`) {
          captureScreenshot(false)
        }
        logger.debug(s"Evaluated javascript: $javascript, result='$result'")
        if (result.isInstanceOf[Boolean] && result.asInstanceOf[Boolean]) {
          Thread.sleep(WebSettings.`gwen.web.throttle.msecs`)
        }
      }
    }
  
  /**
    * Injects and executes a javascript predicate on the current page.
    * 
    * @param javascript the script predicate expression to execute
    * @param params optional parameters to the script
    */
  def executeScriptPredicate(javascript: String, params: Any*): Boolean = 
    executeScript(s"return $javascript", params.map(_.asInstanceOf[AnyRef]) : _*).asInstanceOf[Boolean]
  
  /**
    * Waits for a given condition to be true. Errors on time out 
    * after "gwen.web.wait.seconds" (default is 10 seconds)
    * 
    * @param reason the reason for waiting (used to report timeout error)
    * @param condition the boolean condition to wait for (until true)
    */
  def waitUntil(reason: String)(condition: => Boolean) {
    waitUntil(reason, WebSettings.`gwen.web.wait.seconds`) { condition }
  }
  
  /**
    * Waits or a given condition to be true. Errors on time out 
    * after "gwen.web.wait.seconds" (default is 10 seconds)
    * 
    * @param condition the boolean condition to wait for (until true)
    */
  def waitUntil(condition: => Boolean) {
    waitUntil(WebSettings.`gwen.web.wait.seconds`) { condition }
  }
  
  /**
    * Waits for a given condition to be true for a given number of seconds. 
    * Errors after given timeout out seconds.
    * 
    * @param reason the reason for waiting (used to report timeout error)
    * @param timeoutSecs the number of seconds to wait before timing out
    * @param condition the boolean condition to wait for (until true)
    */
  def waitUntil(reason: String, timeoutSecs: Long)(condition: => Boolean) {
    waitUntil(Some(reason), timeoutSecs)(condition)
  }
  
  /**
    * Waits for a given condition to be true for a given number of seconds. 
    * Errors on given timeout out seconds.
    * 
    * @param timeoutSecs the number of seconds to wait before timing out
    * @param condition the boolean condition to wait for (until true)
    */
  def waitUntil(timeoutSecs: Long)(condition: => Boolean) {
    waitUntil(None, timeoutSecs)(condition)
  }
  
  /**
    * Waits until a given condition is ready for a given number of seconds. 
    * Errors on given timeout out seconds.
    * 
    * @param reason optional reason for waiting (used to report timeout error)
    * @param timeoutSecs the number of seconds to wait before timing out
    * @param condition the boolean condition to wait for (until true)
    */
  private def waitUntil(reason: Option[String], timeoutSecs: Long)(condition: => Boolean) {
    def doWaitUntil(webDriver: WebDriver, timeout: Long) {
      new WebDriverWait(webDriver, timeout).until(
          new ExpectedCondition[Boolean] {
            override def apply(driver: WebDriver): Boolean = condition
          }
        )
    }
    // some drivers intermittently throw javascript errors, so have to track timeout and retry
    val start = System.nanoTime()
    withWebDriver { webDriver =>
      reason foreach { logger.info(_) }
      var timeout = timeoutSecs
      while (timeout > -1) {
        try {
          doWaitUntil(webDriver, timeout)
          timeout = -1
        } catch {
          case e: TimeoutException =>
            throw e
          case e: WebDriverException =>
            Thread.sleep(WebSettings`gwen.web.throttle.msecs`)
            timeout = timeoutSecs - ((System.nanoTime() - start) / 1000000000L)
            if (timeout <= 0) throw e
        }
      }
    }
  }
  
  /**
    * Highlights and then un-highlights a browser element.
    * Uses pure javascript, as suggested by https://github.com/alp82.
    * The duration of the highlight lasts for `gwen.web.throttle.msecs`.
    * The look and feel of the highlight is controlled by the 
    * `gwen.web.highlight.style` setting.
    *
    * @param element the element to highlight
    */
  def highlight(element: WebElement) {
    val msecs = WebSettings`gwen.web.throttle.msecs`;
    if (msecs > 0) {
      val style = WebSettings.`gwen.web.highlight.style` 
      executeScript(s"element = arguments[0]; type = element.getAttribute('type'); if (('radio' == type || 'checkbox' == type) && element.parentElement.getElementsByTagName('input').length == 1) { element = element.parentElement; } original_style = element.getAttribute('style'); element.setAttribute('style', original_style + '; ${style}'); setTimeout(function() { element.setAttribute('style', original_style); }, ${msecs});", element)(WebSettings.`gwen.web.capture.screenshots.highlighting`)
      Thread.sleep(msecs);
    }
  }
  
  /**
    * Add a list of error attachments which includes the current 
    * screenshot and all current error attachments.
    * 
    * @param failed the failed status
    */
  override def addErrorAttachments(failure: Failed): Unit = {
    super.addErrorAttachments(failure)
    execute(captureScreenshot(true))
  }
    
  /**
    * Performs a function on a web element and transparently re-locates elements and 
    * re-attempts the function if the web driver throws an exception.
    * 
    * @param action the action string
    * @param elementBinding the web element locator binding
    * @param f the function to perform on the element
    */
  def withWebElement[T](action: String, elementBinding: LocatorBinding)(f: WebElement => T): T =
     withWebElement(Some(action), elementBinding)(f)
  
  /**
    * Performs a function on a web element and transparently re-locates elements and 
    * re-attempts the function if the web driver throws an exception.
    * 
    * @param elementBinding the web element locator binding
    * @param f the function to perform on the element
    */
  def withWebElement[T](elementBinding: LocatorBinding)(f: WebElement => T): T =
     withWebElement(None, elementBinding)(f)
     
  /**
    * Performs a function on a web element and transparently re-locates elements and 
    * re-attempts the function if the web driver throws an exception.
    * 
    * @param action optional action string
    * @param elementBinding the web element locator binding
    * @param f the function to perform on the element
    */
  private def withWebElement[T](action: Option[String], elementBinding: LocatorBinding)(f: WebElement => T): T = {
    val wHandle = elementBinding.container.map(_ => withWebDriver(_.getWindowHandle))
    try {
      val webElement = locate(this, elementBinding)
      action.foreach { actionString =>
        logger.debug(s"${actionString match {
          case "click" => "Clicking"
          case "submit" => "Submitting"
          case "check" => "Checking"
          case "uncheck" => "Unchecking"
         }} ${elementBinding.element}")
      }
      f(webElement) tap { result =>
        if (WebSettings.`gwen.web.capture.screenshots`) {
          captureScreenshot(false)
        }
      }
    } catch {
      case _: WebDriverException =>
        f(locate(this, elementBinding))
    } finally {
      wHandle foreach { handle =>
        withWebDriver { driver =>
          driver.switchTo().window(handle)
        }
      }
    }
  }
  
  /**
    * Gets a bound value from memory. A search for the value is made in 
    * the following order and the first value found is returned:
    *  - Web element text on the current page
    *  - Currently active page scope
    *  - The global feature scope
    *  - Settings
    *  
    * @param name the name of the bound value to find
    */
  override def getBoundReferenceValue(name: String): String = {
    if (name == "the current URL") captureCurrentUrl()
    (Try(getLocatorBinding(name)) match {
      case Success(binding) =>
        Try(execute(getElementText(binding)).get) match {
          case Success(text) => text.getOrElse(getAttribute(name))
          case Failure(_) => getAttribute(name)
        }
      case Failure(_) => getAttribute(name)
    }) tap { value =>
      logger.debug(s"getBoundReferenceValue(${name})='${value}'")
    }
  }
  
  def captureCurrentUrl() = 
    featureScope.set("the current URL", execute(withWebDriver(_.getCurrentUrl()) tap { content => 
      addAttachment("the current URL", "txt", content) 
    }).getOrElse("$[currentUrl]"))
  
  /**
    * Gets the text value of a web element on the current page. 
    * A search for the text is made in the following order and the first value 
    * found is returned:
    *  - Web element text
    *  - Web element text attribute
    *  - Web element value attribute
    * If a value is found, its value is bound to the current page 
    * scope as `name/text`.
    * 
    * @param elementBinding the web element locator binding
    */
  private def getElementText(elementBinding: LocatorBinding): Option[String] = 
    (withWebElement(elementBinding) { webElement =>
      (Option(webElement.getText) match {
        case None | Some("") => 
          Option(webElement.getAttribute("text")) match {
          case None | Some("") => 
            Option(webElement.getAttribute("value")) match {
            case None | Some("") => 
              Option(executeScript("(function(element){return element.innerText || element.textContent || ''})(arguments[0]);", webElement).asInstanceOf[String])
            case value => value
          }
          case value => value
        }
        case value => value
      }) tap { text => 
        bindAndWait(elementBinding.element, "text", text.getOrElse(null))
      }
    }) tap { value =>
      logger.debug(s"getElementText(${elementBinding.element})='${value}'")
    }
  
  /**
    * Gets the selected text of a dropdown web element on the current page. 
    * If a value is found, its value is bound to the current page 
    * scope as `name/selectedText`.
    * 
    * @param name the web element name
    */
  private def getSelectedElementText(name: String): String = {
    val elementBinding = getLocatorBinding(name)
    (withWebElement(elementBinding) { webElement =>
      val select = new Select(webElement) 
      (Option(select.getAllSelectedOptions().asScala.map(_.getText()).mkString(",")) match {
        case None | Some("") => 
          select.getAllSelectedOptions().asScala.map(_.getAttribute("text")).mkString(",")
        case Some(value) => value
      }) tap { text => 
        bindAndWait(elementBinding.element, "selectedText", text)
      }
    }) tap { value =>
      logger.debug(s"getSelectedElementText(${elementBinding.element})='${value}'")
    }
  }
  
   /**
    * Gets the selected value of a dropdown web element on the current page. 
    * If a value is found, its value is bound to the current page 
    * scope as `name/selectedValue`.
    * 
    * @param name the web element name
    */
  private def getSelectedElementValue(name: String): String = {
    val elementBinding = getLocatorBinding(name)
    (withWebElement(elementBinding) { webElement =>
      new Select(webElement).getAllSelectedOptions().asScala.map(_.getAttribute("value")).mkString(",") tap { value => 
        bindAndWait(elementBinding.element, "selectedValue", value)
      }
    }) tap { value =>
      logger.debug(s"getSelectedElementValue(${elementBinding.element})='${value}'")
    }
  }
  
  /**
   * Gets an element's selected value(s).
   * 
   * @param name the name of the element
   * @param selection `text` to get selected option text, `value` to get
   *        selected option value
   * @return the selected value or a comma seprated string containing all 
   * the selected values if multiple values are selected.
   */
  def getElementSelection(name: String, selection: String) = execute {
    selection.trim match {
      case "text" => getSelectedElementText(name)
      case _ => getSelectedElementValue(name)
    }
  }.getOrElse(s"$$[$name $selection]")
  
  /**
    * Gets a bound attribute value from the visible scope.
    *  
    * @param name the name of the bound attribute to find
    * @param attScopes the attribute scopes to search in
    */
  def getAttribute(name: String): String = {
    val attScopes = scopes.visible.filterAtts{case (n, _) => n.startsWith(name)}
    (attScopes.findEntry { case (n, v) => n.matches(s"""$name(/(text|javascript|xpath.+|regex.+|json path.+|sysproc|file))?""") && v != "" } map {
      case (n, v) => 
        if (n == s"$name/text") v
        else if (n == s"$name/javascript") 
          execute(Option(executeScript(s"return ${interpolate(v)(getBoundReferenceValue)}")).map(_.toString).getOrElse("")).getOrElse(s"$$[javascript:$v]")
        else if (n.startsWith(s"$name/xpath")) {
          val source = interpolate(getBoundReferenceValue(attScopes.get(s"$name/xpath/source")))(getBoundReferenceValue)
          val targetType = interpolate(attScopes.get(s"$name/xpath/targetType"))(getBoundReferenceValue)
          val expression = interpolate(attScopes.get(s"$name/xpath/expression"))(getBoundReferenceValue)
          execute(evaluateXPath(expression, source, XMLNodeType.withName(targetType))).getOrElse(s"$$[xpath:$expression]")
        }
        else if (n.startsWith(s"$name/regex")) {
          val source = interpolate(getBoundReferenceValue(attScopes.get(s"$name/regex/source")))(getBoundReferenceValue)
          val expression = interpolate(attScopes.get(s"$name/regex/expression"))(getBoundReferenceValue)
          execute(extractByRegex(expression, source)).getOrElse(s"$$[regex:$expression]")
        }
        else if (n.startsWith(s"$name/json path")) {
          val source = interpolate(getBoundReferenceValue(attScopes.get(s"$name/json path/source")))(getBoundReferenceValue)
          val expression = interpolate(attScopes.get(s"$name/json path/expression"))(getBoundReferenceValue)
          execute(evaluateJsonPath(expression, source)).getOrElse(s"$$[json path:$expression]")
        }
        else if (n == s"$name/sysproc") execute(v.!!).map(_.trim).getOrElse(s"$$[sysproc:$v]")
        else if (n == s"$name/file") {
          val filepath = (interpolate(v))(getBoundReferenceValue)
          execute {
            if (new File(filepath).exists()) {
              Source.fromFile(filepath).mkString
            } else throw new FileNotFoundException(s"File bound to '$name' not found: $filepath")
          } getOrElse(s"$$[file:$v]")
        } 
        else v
    }).getOrElse {
      execute(super.getBoundReferenceValue(name)).getOrElse { 
        Try(super.getBoundReferenceValue(name)).getOrElse {
          Try(getLocatorBinding(name).lookup).getOrElse {
            unboundAttributeError(name)
          }
        }
      }
    } tap { value =>
      logger.debug(s"getAttribute(${name})='${value}'")
    }
  }
  
  def boundAttributeOrSelection(element: String, selection: String): () => String = () => Option(selection) match {
    case None => getBoundReferenceValue(element)
    case Some(sel) => 
      try { 
        getBoundReferenceValue(element + sel)
      } catch {
        case _: UnboundAttributeException => getElementSelection(element, sel)
        case e: Throwable => throw e
      }
  }
  
  /**
   * Gets a web element binding.
   * 
   * @param element the name of the web element
   */
  def getLocatorBinding(element: String): LocatorBinding = {
    val locatorBinding = s"$element/locator";
    scopes.getOpt(locatorBinding) match {
      case Some(locator) =>
        val lookupBinding = interpolate(s"$element/locator/$locator")(getBoundReferenceValue)
        scopes.getOpt(lookupBinding) match {
          case Some(expression) =>
            val expr = interpolate(expression)(getBoundReferenceValue)
            val container = scopes.getOpt(interpolate(s"$element/locator/$locator/container")(getBoundReferenceValue))
            if (isDryRun) {
              container.foreach(c => getLocatorBinding(c))
            }
            LocatorBinding(element, locator, expr, container)
          case None => throw new LocatorBindingException(element, s"locator lookup binding not found: ${lookupBinding}")
        }
      case None => throw new LocatorBindingException(element, s"locator binding not found: ${locatorBinding}")
    }
  }
  
  /**
    * Binds the given name and value to a given action (name/action=value) 
    * and then waits for any bound post conditions to be satisfied.
    * 
    * @param name the name to bind the value to
    * @param action the action to bind the value to
    * @param value the value to bind
    */
  def bindAndWait(element: String, action: String, value: String) {
    scopes.set(s"$element/$action", value)
    
    // sleep if wait time is configured for this action
    scopes.getOpt(s"$element/$action/wait") foreach { secs => 
      logger.info(s"Waiting for ${secs} second(s) (post-$action wait)")
      Thread.sleep(secs.toLong * 1000)
    }
    
    // wait for javascript post condition if one is configured for this action
    scopes.getOpt(s"$element/$action/condition") foreach { condition =>
      val javascript = scopes.get(s"$condition/javascript")
      logger.debug(s"Waiting for script to return true: ${javascript}")
      waitUntil(s"Waiting until $condition (post-$action condition)") {
        executeScriptPredicate(javascript)
      }
    }
  }
  
  /** Gets the title of the current page in the browser.*/
  def getTitle: String = withWebDriver { webDriver => 
    webDriver.getTitle() tap { title =>
      bindAndWait("page", "title", title)
    }
  }
  
  /**
    * Sends a value to a web element (one character at a time).
    * 
    * @param elementBinding the web element locator binding
    * @param value the value to send
    * @param clearFirst true to clear field first (if element is a text field)
    * @param sendEnterKey true to send the Enter key after sending the value
    */
  def sendKeys(elementBinding: LocatorBinding, value: String, clearFirst: Boolean, sendEnterKey: Boolean) {
    val element = elementBinding.element
    withWebElement(elementBinding) { webElement =>
      if (clearFirst) {
        clearText(webElement, element)
      }
      webElement.sendKeys(value)
      bindAndWait(element, "type", value)
      if (sendEnterKey) {
        webElement.sendKeys(Keys.RETURN)
        bindAndWait(element, "enter", "true")
      }
    }
  }
  
  def clearText(elementBinding: LocatorBinding) {
    withWebElement(elementBinding) { clearText(_, elementBinding.element) }
  }
  
  private def clearText(webElement: WebElement, name: String) {
    webElement.clear()
    bindAndWait(name, "clear", "true")
  }
  
  /**
    * Selects a value in a dropdown (select control) by visible text.
    * 
    * @param elementBinding the web element locator binding
    * @param value the value to select
    */
  def selectByVisibleText(elementBinding: LocatorBinding, value: String) {
    withWebElement(elementBinding) { webElement =>
      logger.debug(s"Selecting '$value' in ${elementBinding.element} by text")
      new Select(webElement).selectByVisibleText(value)
      bindAndWait(elementBinding.element, "select", value)
    }
  }
  
  /**
    * Selects a value in a dropdown (select control) by value.
    * 
    * @param elementBinding the web element locator binding
    * @param value the value to select
    */
  def selectByValue(elementBinding: LocatorBinding, value: String) {
    withWebElement(elementBinding) { webElement =>
      logger.debug(s"Selecting '$value' in ${elementBinding.element} by value")
      new Select(webElement).selectByValue(value)
      bindAndWait(elementBinding.element, "select", value)
    }
  }
  
  /**
    * Selects a value in a dropdown (select control) by index.
    * 
    * @param elementBinding the web element locator binding
    * @param index the index to select (first index is 1)
    */
  def selectByIndex(elementBinding: LocatorBinding, index: Int) {
    withWebElement(elementBinding) { webElement =>
      logger.debug(s"Selecting option in ${elementBinding.element} by index: $index")
      val select = new Select(webElement)
      select.selectByIndex(index)
      bindAndWait(elementBinding.element, "select", select.getFirstSelectedOption().getText())
    }
  }
  
  def performAction(action: String, elementBinding: LocatorBinding) {
    val actionBinding = scopes.getOpt(s"${elementBinding.element}/action/$action/javascript")
    actionBinding match {
      case Some(javascript) =>
        performScriptAction(action, javascript, elementBinding)
      case None =>
        action match {
          case "click" =>
            withWebElement(action, elementBinding) { webElement =>
              val clicked = executeScriptPredicate("(function(element){try{element.focus(); element.click(); return true;}catch(err){return false;}})(arguments[0]);", webElement)
              if (!clicked) {
                webElement.click()
              }  
            }
          case _ =>
            withWebElement(action, elementBinding) { webElement =>
              action match {
                case "submit" => webElement.submit
                case "check" => if (!webElement.isSelected()) webElement.sendKeys(Keys.SPACE)
                case "uncheck" => if (webElement.isSelected()) webElement.sendKeys(Keys.SPACE)
              }
            }
        }
    }
    bindAndWait(elementBinding.element, action, "true")
  }
  
  private def performScriptAction(action: String, javascript: String, elementBinding: LocatorBinding) {
    withWebElement(action, elementBinding) { webElement =>
      executeScript(s"(function(element) { $javascript })(arguments[0])", webElement) 
      bindAndWait(elementBinding.element, action, "true")
    }
  }
  
  def performActionIn(action: String, elementBinding: LocatorBinding, contextBinding: LocatorBinding) {
    withWebElement(action, contextBinding) { contextElement =>
      withWebElement(action, elementBinding) { webElement =>
        withWebDriver { driver =>
          var actions = new Actions(driver).moveToElement(contextElement).moveToElement(webElement)
          action match {
            case "click" => actions = actions.click
            case "check" => if (!webElement.isSelected()) actions = actions.sendKeys(Keys.SPACE)
            case "uncheck" => if (webElement.isSelected()) actions = actions.sendKeys(Keys.SPACE)
          }
          actions.build.perform()
        }
        bindAndWait(elementBinding.element, action, "true")
      }
    }
  }
  
  /**
    * Waits for text to appear in the given web element.
    * 
    * @param elementBinding the web element locator binding 
    */
  def waitForText(elementBinding: LocatorBinding): Boolean = 
    getElementText(elementBinding).map(_.length()).getOrElse(0) > 0
  
  /**
   * Scrolls an element into view.
   * 
   * @param elementBinding the web element locator binding
   * @param scrollTo scroll element into view, options are: top or bottom
   */
  def scrollIntoView(elementBinding: LocatorBinding, scrollTo: ScrollTo.Value) {
    withWebElement(elementBinding) { scrollIntoView(_, scrollTo) }
  }
  
  /**
   * Scrolls the given web element into view.
   * 
   * @param webElement the web element to scroll to
   * @param scrollTo scroll element into view, options are: top or bottom
   */
  def scrollIntoView(webElement: WebElement, scrollTo: ScrollTo.Value) {
    executeScript(s"var elem = arguments[0]; if (typeof elem !== 'undefined' && elem != null) { elem.scrollIntoView(${scrollTo == ScrollTo.top}); }", webElement)
  }
  
  /**
    * Gets the actual value of an attribute and compares it with an expected value or condition.
    * 
    * @param name the name of the attribute being compared
    * @param expected the expected value, regex, xpath, or json path
    * @param actual the actual value of the element
    * @param operator the comparison operator
    * @param negate true to negate the result
    * @param env the web environment context
    * @return true if the actual value matches the expected value
    */
  def compare(name: String, expected: String, actual: () => String, operator: String, negate: Boolean) = {
    var actualValue = ""
    val result = Try {
      waitUntil {
        actualValue = actual()
        if (actualValue != null) {
          super.compare(expected, actualValue, operator, negate)
        } else false
      }
    } match {
      case Success(_) => true
      case Failure(failure) => failure.getCause match {
        case _: FileNotFoundException => throw failure
        case _ => false
      }
    }
    assert(result, s"Expected $name to ${if(negate) "not " else ""}$operator '$expected' but got '$actualValue'")
  }
  
  /**
   * Adds web engine dsl steps to super implementation. The entries 
   * returned by this method are used for tab completion in the REPL.
   */
  override def dsl: List[String] = 
    Source.fromInputStream(getClass.getResourceAsStream("/gwen-web.dsl")).getLines().toList ++ super.dsl
  
}
