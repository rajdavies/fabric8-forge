/**
 * Copyright 2005-2015 Red Hat, Inc.
 * <p/>
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.fabric8.forge.camel.commands.project;

import io.fabric8.camel.tooling.util.CamelModelHelper;
import io.fabric8.camel.tooling.util.RouteXml;
import io.fabric8.camel.tooling.util.XmlModel;
import io.fabric8.forge.addon.utils.CommandHelpers;
import io.fabric8.forge.addon.utils.Indenter;
import io.fabric8.forge.camel.commands.project.completer.XmlFileCompleter;
import io.fabric8.forge.camel.commands.project.dto.ContextDto;
import io.fabric8.forge.camel.commands.project.dto.NodeDto;
import io.fabric8.forge.camel.commands.project.dto.NodeDtos;
import io.fabric8.forge.camel.commands.project.dto.OutputFormat;
import io.fabric8.forge.camel.commands.project.dto.RouteDto;
import io.fabric8.utils.Files;
import io.fabric8.utils.Strings;
import org.apache.camel.model.FromDefinition;
import org.apache.camel.model.OptionalIdentifiedDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spring.CamelContextFactoryBean;
import org.jboss.forge.addon.projects.Project;
import org.jboss.forge.addon.projects.dependencies.DependencyInstaller;
import org.jboss.forge.addon.ui.context.UIBuilder;
import org.jboss.forge.addon.ui.context.UIContext;
import org.jboss.forge.addon.ui.context.UIExecutionContext;
import org.jboss.forge.addon.ui.input.UISelectOne;
import org.jboss.forge.addon.ui.metadata.UICommandMetadata;
import org.jboss.forge.addon.ui.metadata.WithAttributes;
import org.jboss.forge.addon.ui.result.Result;
import org.jboss.forge.addon.ui.result.Results;
import org.jboss.forge.addon.ui.util.Categories;
import org.jboss.forge.addon.ui.util.Metadata;

import javax.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.fabric8.forge.addon.utils.Files.joinPaths;
import static io.fabric8.forge.camel.commands.project.helper.CollectionHelper.first;
import static io.fabric8.forge.camel.commands.project.helper.OutputFormatHelper.toJson;

public class CamelGetRoutesXmlCommand extends AbstractCamelProjectCommand {

    @Inject
    @WithAttributes(label = "XML File", required = true, description = "The XML file to use (either Spring or Blueprint)")
    private UISelectOne<String> xml;

    @Inject
    @WithAttributes(label = "Format", defaultValue = "Text", description = "Format output as text or json")
    private UISelectOne<OutputFormat> format;

    @Inject
    private DependencyInstaller dependencyInstaller;

    private RouteXml routeXml = new RouteXml();

    @Override
    public UICommandMetadata getMetadata(UIContext context) {
        return Metadata.forCommand(CamelGetRoutesXmlCommand.class).name(
                "Camel: Get Routes XML").category(Categories.create(CATEGORY))
                .description("Gets the overview of the routes in a project for a given XML file");
    }

    @Override
    public void initializeUI(UIBuilder builder) throws Exception {
        Project project = getSelectedProject(builder.getUIContext());

        XmlFileCompleter xmlFileCompleter = createXmlFileCompleter(project);
        Set<String> files = xmlFileCompleter.getFiles();

        // use value choices instead of completer as that works better in web console
        xml.setValueChoices(files);
        if (files.size() == 1) {
            // lets default the value if there's only one choice
            xml.setDefaultValue(first(files));
        }
        builder.add(xml).add(format);
    }

    @Override
    public Result execute(UIExecutionContext context) throws Exception {
        Project project = getSelectedProject(context);

        String xmlFileName = joinPaths("src/main/resources", xml.getValue());
        File xmlFile = CommandHelpers.getProjectContextFile(context.getUIContext(), project, xmlFileName);
        if (!Files.isFile(xmlFile)) {
            return Results.fail("No file found for: " + xmlFileName);
        }

        List<ContextDto> camelContexts = parseCamelContexts(xmlFile);
        String result = formatResult(camelContexts);
        return Results.success(result);
    }

    protected List<ContextDto> parseCamelContexts(File xmlFile) throws Exception {
        List<ContextDto> camelContexts = new ArrayList<>();

        XmlModel xmlModel = routeXml.unmarshal(xmlFile);

        // TODO we don't handle multiple contexts inside an XML file!
        CamelContextFactoryBean contextElement = xmlModel.getContextElement();
        String name = contextElement.getId();
        List<RouteDefinition> routeDefs = contextElement.getRoutes();
        ContextDto context = new ContextDto(name);
        camelContexts.add(context);
        String key = name;
        if (Strings.isNullOrBlank(key)) {
            key = "camelContext" + camelContexts.size();
        }
        context.setKey(key);
        List<NodeDto> routes = createRouteDtos(routeDefs, context);
        context.setChildren(routes);
        return camelContexts;
    }

    protected List<NodeDto> createRouteDtos(List<RouteDefinition> routeDefs, ContextDto context) {
        List<NodeDto> answer = new ArrayList<>();
        for (RouteDefinition def : routeDefs) {
            RouteDto route = new RouteDto();
            route.setId(def.getId());
            route.setLabel(CamelModelHelper.getDisplayText(def));
            route.setDescription(CamelModelHelper.getDescription(def));
            answer.add(route);
            route.defaultKey(context, answer.size());

            addInputs(route, def.getInputs());
            addOutputs(route, def.getOutputs());
        }
        return answer;
    }

    protected static void addInputs(NodeDto owner, List<FromDefinition> inputs) {
        for (FromDefinition input : inputs) {
            addChild(owner, input);
        }
    }

    protected static void addOutputs(NodeDto owner, List<ProcessorDefinition<?>> outputs) {
        for (ProcessorDefinition<?> output : outputs) {
            addChild(owner, output);
        }
    }

    private static NodeDto addChild(NodeDto owner, OptionalIdentifiedDefinition definition) {
        NodeDto node = new NodeDto();
        node.setId(definition.getId());
        node.setLabel(CamelModelHelper.getDisplayText(definition));
        node.setDescription(CamelModelHelper.getDescription(definition));
        node.setPattern(CamelModelHelper.getPatternName(definition));
        owner.addChild(node);
        node.defaultKey(owner, owner.getChildren().size());

        if (definition instanceof ProcessorDefinition) {
            ProcessorDefinition processorDefinition = (ProcessorDefinition) definition;
            addOutputs(node, processorDefinition.getOutputs());
        }
        return node;
    }

    protected String formatResult(List<ContextDto> result) throws Exception {
        OutputFormat outputFormat = format.getValue();
        switch (outputFormat) {
            case JSON:
                return toJson(result);
            default:
                return textResult(result);
        }
    }

    protected String textResult(List<ContextDto> camelContexts) throws Exception {
        StringBuilder buffer = new StringBuilder("\n\n");

        final Indenter out = new Indenter(buffer);
        for (final ContextDto camelContext : camelContexts) {
            NodeDtos.printNode(out, camelContext);
        }
        return buffer.toString();
    }

}