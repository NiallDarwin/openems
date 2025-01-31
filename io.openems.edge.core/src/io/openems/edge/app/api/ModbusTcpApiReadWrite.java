package io.openems.edge.app.api;

import static io.openems.edge.core.appmanager.formly.builder.SelectBuilder.DEFAULT_COMPONENT_2_LABEL;
import static io.openems.edge.core.appmanager.formly.builder.SelectBuilder.DEFAULT_COMPONENT_2_VALUE;
import static io.openems.edge.core.appmanager.formly.enums.InputType.NUMBER;

import java.util.EnumMap;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Reference;

import com.google.common.collect.Lists;
import com.google.gson.JsonElement;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.function.ThrowingTriFunction;
import io.openems.common.session.Language;
import io.openems.common.types.EdgeConfig;
import io.openems.common.utils.EnumUtils;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.app.api.ModbusTcpApiReadWrite.Property;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.core.appmanager.AbstractEnumOpenemsApp;
import io.openems.edge.core.appmanager.AbstractOpenemsApp;
import io.openems.edge.core.appmanager.AppAssistant;
import io.openems.edge.core.appmanager.AppConfiguration;
import io.openems.edge.core.appmanager.AppDescriptor;
import io.openems.edge.core.appmanager.ComponentUtil;
import io.openems.edge.core.appmanager.ConfigurationTarget;
import io.openems.edge.core.appmanager.Nameable;
import io.openems.edge.core.appmanager.OpenemsApp;
import io.openems.edge.core.appmanager.OpenemsAppCardinality;
import io.openems.edge.core.appmanager.OpenemsAppCategory;
import io.openems.edge.core.appmanager.TranslationUtil;
import io.openems.edge.core.appmanager.dependency.DependencyDeclaration;
import io.openems.edge.core.appmanager.formly.JsonFormlyUtil;

/**
 * Describes a App for ReadWrite Modbus/TCP Api.
 *
 * <pre>
  {
    "appId":"App.Api.ModbusTcp.ReadWrite",
    "alias":"Modbus/TCP-Api Read-Write",
    "instanceId": UUID,
    "image": base64,
    "properties":{
    	"CONTROLLER_ID": "ctrlApiModbusTcp0",
    	"API_TIMEOUT": 60,
    	"COMPONENT_IDS": ["_sum", ...]
    },
    "dependencies": [
    	{
        	"key": "READ_ONLY",
        	"instanceId": UUID
    	}
    ],
    "appDescriptor": {
    	"websiteUrl": {@link AppDescriptor#getWebsiteUrl()}
    }
  }
 * </pre>
 */
@org.osgi.service.component.annotations.Component(name = "App.Api.ModbusTcp.ReadWrite")
public class ModbusTcpApiReadWrite extends AbstractEnumOpenemsApp<Property> implements OpenemsApp {

	public static enum Property implements Nameable {
		// Component-IDs
		CONTROLLER_ID, //
		// Properties
		API_TIMEOUT, //
		COMPONENT_IDS //
		;
	}

	@Activate
	public ModbusTcpApiReadWrite(@Reference ComponentManager componentManager, ComponentContext context,
			@Reference ConfigurationAdmin cm, @Reference ComponentUtil componentUtil) {
		super(componentManager, context, cm, componentUtil);
	}

	@Override
	public AppAssistant getAppAssistant(Language language) {
		var bundle = AbstractOpenemsApp.getTranslationBundle(language);
		return AppAssistant.create(this.getName(language)) //
				.fields(JsonUtils.buildJsonArray() //
						.add(JsonFormlyUtil.buildInput(Property.API_TIMEOUT) //
								.setLabel(TranslationUtil.getTranslation(bundle, "App.Api.apiTimeout.label")) //
								.setDescription(
										TranslationUtil.getTranslation(bundle, "App.Api.apiTimeout.description")) //
								.setDefaultValue(60) //
								.isRequired(true) //
								.setInputType(NUMBER) //
								.setMin(0) //
								.build())
						.add(JsonFormlyUtil.buildSelect(Property.COMPONENT_IDS) //
								.isMulti(true) //
								.isRequired(true) //
								.setLabel(
										TranslationUtil.getTranslation(bundle, this.getAppId() + ".componentIds.label")) //
								.setDescription(TranslationUtil.getTranslation(bundle,
										this.getAppId() + ".componentIds.description")) //
								.setOptions(this.componentManager.getEnabledComponentsOfType(ModbusSlave.class), //
										DEFAULT_COMPONENT_2_LABEL, DEFAULT_COMPONENT_2_VALUE)
								.setDefaultValue(JsonUtils.buildJsonArray() //
										.add("_sum") //
										.build()) //
								.build())
						.build())
				.build();
	}

	@Override
	public AppDescriptor getAppDescriptor() {
		return AppDescriptor.create() //
				.build();
	}

	@Override
	public OpenemsAppCategory[] getCategories() {
		return new OpenemsAppCategory[] { OpenemsAppCategory.API };
	}

	@Override
	public OpenemsAppCardinality getCardinality() {
		return OpenemsAppCardinality.SINGLE;
	}

	@Override
	protected ThrowingTriFunction<ConfigurationTarget, EnumMap<Property, JsonElement>, Language, AppConfiguration, OpenemsNamedException> appConfigurationFactory() {
		return (t, p, l) -> {

			var controllerId = this.getId(t, p, Property.CONTROLLER_ID, "ctrlApiModbusTcp0");
			var apiTimeout = EnumUtils.getAsInt(p, Property.API_TIMEOUT);
			var controllerIds = EnumUtils.getAsJsonArray(p, Property.COMPONENT_IDS);

			// remove self if selected
			for (var i = 0; i < controllerIds.size(); i++) {
				if (controllerIds.get(i).getAsString().equals(controllerId)) {
					controllerIds.remove(i);
					break;
				}
			}

			var components = Lists.newArrayList(//
					new EdgeConfig.Component(controllerId, this.getName(l), "Controller.Api.ModbusTcp.ReadWrite",
							JsonUtils.buildJsonObject() //
									.addProperty("apiTimeout", apiTimeout) //
									.add("component.ids", controllerIds).build()) //
			);

			final var schedulerIds = Lists.newArrayList(//
					"ctrlEmergencyCapacityReserve0", //
					controllerId, //
					"ctrlGridOptimizedCharge0", //
					"ctrlEssSurplusFeedToGrid0", //
					"ctrlBalancing0" //
			);

			var dependencies = Lists.newArrayList(//
					new DependencyDeclaration("READ_ONLY", //
							DependencyDeclaration.CreatePolicy.NEVER, //
							DependencyDeclaration.UpdatePolicy.ALWAYS, //
							DependencyDeclaration.DeletePolicy.NEVER, //
							DependencyDeclaration.DependencyUpdatePolicy.ALLOW_ONLY_UNCONFIGURED_PROPERTIES, //
							DependencyDeclaration.DependencyDeletePolicy.ALLOWED, //
							DependencyDeclaration.AppDependencyConfig.create() //
									.setAppId("App.Api.ModbusTcp.ReadOnly") //
									.setProperties(JsonUtils.buildJsonObject() //
											.addProperty(ModbusTcpApiReadOnly.Property.ACTIVE.name(),
													t == ConfigurationTarget.DELETE) //
											.build())
									.build()) //
			);

			return new AppConfiguration(components, schedulerIds, null, dependencies);
		};
	}

	@Override
	protected Class<Property> getPropertyClass() {
		return Property.class;
	}

}
