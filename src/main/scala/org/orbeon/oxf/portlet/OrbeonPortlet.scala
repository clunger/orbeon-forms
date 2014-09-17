/**
 * Copyright (C) 2011 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.portlet

import java.io.ByteArrayInputStream

import org.orbeon.oxf.fr.embedding._
import org.orbeon.oxf.http._

import collection.JavaConverters._
import org.orbeon.oxf.portlet.Portlet2ExternalContext.BufferedResponse
import OrbeonPortlet._
import org.orbeon.oxf.common.Version
import org.orbeon.oxf.util.ScalaUtils._
import javax.portlet._
import org.orbeon.oxf.pipeline.api.{ExternalContext, PipelineContext}
import org.orbeon.oxf.webapp.{WebAppContext, ProcessorService, ServletPortlet}
import org.orbeon.oxf.util.DynamicVariable

// For backward compatibility
class OrbeonPortlet2         extends OrbeonPortlet
class OrbeonPortletDelegate  extends OrbeonPortlet
class OrbeonPortlet2Delegate extends OrbeonPortlet

/**
 * This is the Portlet (JSR-286) entry point of Orbeon.
 *
 * Several servlets and portlets can be used in the same web application. They all share the same context initialization
 * parameters, but each servlet and portlet can be configured with its own main processor and inputs.
 *
 * All servlets and portlets instances in a given web app share the same resource manager.
 *
 * Ideas for improvements:
 *
 * - support writing render/resource directly without buffering when possible (not action or async load)
 * - warning message if user is re-rendering a page which is the result of an action
 * - implement improved caching of page with replay of XForms events, see:
 *   http://wiki.orbeon.com/forms/projects/xforms-improved-portlet-support#TOC-XForms-aware-caching-of-portlet-out
 */
class OrbeonPortlet extends GenericPortlet with ServletPortlet with BufferedPortlet {

    private implicit val logger = ProcessorService.Logger

    // For BufferedPortlet
    def title(request: RenderRequest) = getTitle(request)
    def portletContext = getPortletContext

    // For ServletPortlet
    def logPrefix = "Portlet"

    // Immutable map of portlet parameters
    lazy val initParameters =
        getInitParameterNames.asScala map
            (n ⇒ n → getInitParameter(n)) toMap

    case class AsyncContext(externalContext: ExternalContext, pipelineContext: Option[PipelineContext])

    // Portlet init
    override def init(): Unit =
        withRootException("initialization", new PortletException(_)) {
            // Obtain WebAppContext as that will initialize the resource manager if needed, which is required by
            // Version
            val webAppContext = WebAppContext(getPortletContext)
            Version.instance.requirePEFeature("Orbeon Forms portlet") // this is a PE feature
            init(webAppContext, Some("oxf.portlet-initialized-processor." → "oxf.portlet-initialized-processor.input."))
        }

    // Portlet destroy
    override def destroy(): Unit =
        withRootException("destruction", new PortletException(_)) {
            destroy(Some("oxf.portlet-destroyed-processor." → "oxf.portlet-destroyed-processor.input."))
        }

    // Portlet render
    override def render(request: RenderRequest, response: RenderResponse): Unit =
        currentPortlet.withValue(this) {
            withRootException("render", new PortletException(_)) {
                implicit val ctx = new PortletEmbeddingContextWithResponse(getPortletContext, request, response, null)
                bufferedRender(request, response, callService(directContext(request)))
            }
        }

    // Portlet action
    override def processAction(request: ActionRequest, response: ActionResponse): Unit =
        currentPortlet.withValue(this) {
            withRootException("action", new PortletException(_)) {
                implicit val ctx = new PortletEmbeddingContext(getPortletContext, request, response, null)
                bufferedProcessAction(request, response, callService(directContext(request)))
            }
        }

    // Portlet resource
    override def serveResource(request: ResourceRequest, response: ResourceResponse) =
        currentPortlet.withValue(this) {
            withRootException("resource", new PortletException(_)) {
                implicit val ctx = new PortletEmbeddingContextWithResponse(getPortletContext, request, response, null)
                directServeResource(request, response)
            }
        }

    private def directContext(request: PortletRequest): AsyncContext = {
        val pipelineContext = new PipelineContext
        val externalContext = new Portlet2ExternalContext(pipelineContext, webAppContext, request, true)

        AsyncContext(externalContext, Some(pipelineContext))
    }

    // Call the Orbeon Forms pipeline processor service
    private def callService(context: AsyncContext): StreamedContentOrRedirect = {
        processorService.service(context.pipelineContext getOrElse new PipelineContext, context.externalContext)
        bufferedResponseToResponse(context.externalContext.getResponse.asInstanceOf[BufferedResponse])
    }

    private def directServeResource(request: ResourceRequest, response: ResourceResponse)(implicit ctx: EmbeddingContextWithResponse): Unit = {
        // Process request
        val pipelineContext = new PipelineContext
        val externalContext = new Portlet2ExternalContext(pipelineContext, webAppContext, request, true)
        processorService.service(pipelineContext, externalContext)

        // Write out the response
        val directResponse = externalContext.getResponse.asInstanceOf[BufferedResponse]
        Option(directResponse.getContentType) foreach response.setContentType
        APISupport.writeResponseBody(
            BufferedContent(
                directResponse.getBytes,
                Option(directResponse.getContentType),
                None
            )
        )
    }

    private def bufferedResponseToResponse(bufferedResponse: BufferedResponse): StreamedContentOrRedirect =
        if (bufferedResponse.isRedirect)
            Redirect(bufferedResponse.getRedirectLocation, bufferedResponse.isRedirectIsExitPortal)
        else {
            val bytes = bufferedResponse.getBytes
            StreamedContent(
                new ByteArrayInputStream(bytes),
                Option(bufferedResponse.getContentType),
                Some(bytes.size.toLong),
                Option(bufferedResponse.getTitle)
            )
        }
}

object OrbeonPortlet {
    // As of 2012-05-08, used only by LocalPortletSubmission to get access to ProcessorService
    val currentPortlet = new DynamicVariable[OrbeonPortlet]
}