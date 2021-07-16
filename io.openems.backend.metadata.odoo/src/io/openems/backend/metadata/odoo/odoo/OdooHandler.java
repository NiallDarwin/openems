package io.openems.backend.metadata.odoo.odoo;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.openems.backend.metadata.odoo.Config;
import io.openems.backend.metadata.odoo.Field;
import io.openems.backend.metadata.odoo.Field.Partner;
import io.openems.backend.metadata.odoo.Field.SetupProtocol;
import io.openems.backend.metadata.odoo.MyEdge;
import io.openems.backend.metadata.odoo.MyUser;
import io.openems.backend.metadata.odoo.OdooMetadata;
import io.openems.backend.metadata.odoo.odoo.OdooUtils.SuccessResponseAndHeaders;
import io.openems.common.exceptions.OpenemsError;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.utils.JsonUtils;
import io.openems.common.utils.ObjectUtils;
import io.openems.common.utils.PasswordUtils;

public class OdooHandler {

	protected final OdooMetadata parent;

	private final Logger log = LoggerFactory.getLogger(OdooHandler.class);
	private final Credentials credentials;

	public OdooHandler(OdooMetadata parent, Config config) {
		this.parent = parent;
		this.credentials = Credentials.fromConfig(config);
	}

	/**
	 * Writes one field to Odoo Edge model.
	 * 
	 * @param edge        the Edge
	 * @param fieldValues the FieldValues
	 */
	public void writeEdge(MyEdge edge, FieldValue<?>... fieldValues) {
		try {
			OdooUtils.write(this.credentials, Field.EdgeDevice.ODOO_MODEL, new Integer[] { edge.getOdooId() },
					fieldValues);
		} catch (OpenemsException e) {
			this.parent.logError(this.log, "Unable to update Edge [" + edge.getId() + "] " //
					+ "Odoo-ID [" + edge.getOdooId() + "] " //
					+ "Fields [" + Stream.of(fieldValues).map(v -> v.toString()).collect(Collectors.joining(","))
					+ "]: " + e.getMessage());
		}
	}

	/**
	 * Adds a message in Odoo Chatter ('mail.thread').
	 * 
	 * @param edge    the Edge
	 * @param message the message
	 */
	public void addChatterMessage(MyEdge edge, String message) {
		try {
			OdooUtils.addChatterMessage(this.credentials, Field.EdgeDevice.ODOO_MODEL, edge.getOdooId(), message);
		} catch (OpenemsException e) {
			this.parent.logError(this.log, "Unable to add Chatter Message to Edge [" + edge.getId() + "] " //
					+ "Message [" + message + "]" //
					+ ": " + e.getMessage());
		}
	}

	/**
	 * Returns Edge by setupPassword, otherwise an empty {@link Optional}.
	 * 
	 * @param setupPassword to find Edge
	 * @return Edge or empty {@link Optional}
	 */
	public Optional<String> getEdgeIdBySetupPassword(String setupPassword) {
		Domain filter = new Domain(Field.EdgeDevice.SETUP_PASSWORD, "=", setupPassword);

		try {
			int[] search = OdooUtils.search(this.credentials, Field.EdgeDevice.ODOO_MODEL, filter);
			if (search.length == 0) {
				return Optional.empty();
			}

			Map<String, Object> read = OdooUtils.readOne(this.credentials, Field.EdgeDevice.ODOO_MODEL, search[0],
					Field.EdgeDevice.NAME);

			String name = (String) read.get(Field.EdgeDevice.NAME.id());
			if (name == null) {
				return Optional.empty();
			}

			return Optional.of(name);
		} catch (OpenemsException e) {
			this.parent.logInfo(this.log, "Unable to find Edge by setupPassowrd [" + setupPassword + "]");
		}

		return Optional.empty();
	}

	/**
	 * Assigns the given user with given {@link OdooUserRole} to the Edge. If Edge
	 * already assigned to user exit method.
	 * 
	 * @param user     the Odoo user
	 * @param edge     the Odoo edge
	 * @param userRole the Odoo user role
	 * @throws OpenemsException on error
	 */
	public void assignEdgeToUser(MyUser user, MyEdge edge, OdooUserRole userRole) throws OpenemsException {
		this.assignEdgeToUser(user.getOdooId(), edge.getOdooId(), userRole);
	}

	/**
	 * Assigns the given user with given {@link OdooUserRole} to the Edge. If Edge
	 * already assigned to user exit method.
	 * 
	 * @param userId   the Odoo user id
	 * @param edgeId   the Odoo edge
	 * @param userRole the Odoo user role
	 * @throws OpenemsException on error
	 */
	public void assignEdgeToUser(int userId, int edgeId, OdooUserRole userRole) throws OpenemsException {
		int[] found = OdooUtils.search(this.credentials, Field.EdgeDeviceUserRole.ODOO_MODEL,
				new Domain(Field.EdgeDeviceUserRole.USER_ID, "=", userId),
				new Domain(Field.EdgeDeviceUserRole.DEVICE_ID, "=", edgeId));

		if (found.length > 0) {
			return;
		}

		OdooUtils.create(this.credentials, Field.EdgeDeviceUserRole.ODOO_MODEL, //
				new FieldValue<Integer>(Field.EdgeDeviceUserRole.USER_ID, userId), //
				new FieldValue<Integer>(Field.EdgeDeviceUserRole.DEVICE_ID, edgeId), //
				new FieldValue<String>(Field.EdgeDeviceUserRole.ROLE, userRole.getOdooRole()));
	}

	/**
	 * Authenticates a user using Username and Password.
	 * 
	 * @param username the Username
	 * @param password the Password
	 * @return the session_id
	 * @throws OpenemsNamedException on login error
	 */
	public String authenticate(String username, String password) throws OpenemsNamedException {
		JsonObject request = JsonUtils.buildJsonObject() //
				.addProperty("jsonrpc", "2.0") //
				.addProperty("method", "call") //
				.add("params", JsonUtils.buildJsonObject() //
						.addProperty("db", "v12") //
						.addProperty("login", username) //
						.addProperty("password", password) //
						.build()) //
				.build();
		SuccessResponseAndHeaders response = OdooUtils
				.sendJsonrpcRequest(this.credentials.getUrl() + "/web/session/authenticate", request);
		Optional<String> sessionId = getFieldFromSetCookieHeader(response.headers, "session_id");
		if (!sessionId.isPresent()) {
			throw OpenemsError.COMMON_AUTHENTICATION_FAILED.exception();
		} else {
			return sessionId.get();
		}
	}

	/**
	 * Authenticates a user using a Session-ID.
	 * 
	 * @param sessionId the Odoo Session-ID
	 * @return the {@link JsonObject} received from /openems_backend/info.
	 * @throws OpenemsNamedException on error
	 */
	public JsonObject authenticateSession(String sessionId) throws OpenemsNamedException {
		return JsonUtils
				.getAsJsonObject(OdooUtils.sendJsonrpcRequest(this.credentials.getUrl() + "/openems_backend/info",
						"session_id=" + sessionId, new JsonObject()).result);
	}

	/**
	 * Logout a User.
	 * 
	 * @param sessionId the Session-ID
	 * @throws OpenemsNamedException on error
	 */
	public void logout(String sessionId) {
		try {
			OdooUtils.sendJsonrpcRequest(this.credentials.getUrl() + "/web/session/destroy", "session_id=" + sessionId,
					new JsonObject());
		} catch (OpenemsNamedException e) {
			this.log.warn("Unable to logout session [" + sessionId + "]: " + e.getMessage());
		}
	}

	/**
	 * Get field from the 'Set-Cookie' field in HTTP headers.
	 * 
	 * <p>
	 * Per <a href=
	 * "https://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.2">specification</a>
	 * all variants of 'cookie' are accepted.
	 * 
	 * @param headers   the HTTP headers
	 * @param fieldname the field name
	 * @return value as optional
	 */
	public static Optional<String> getFieldFromSetCookieHeader(Map<String, List<String>> headers, String fieldname) {
		for (Entry<String, List<String>> header : headers.entrySet()) {
			String key = header.getKey();
			if (key != null && key.equalsIgnoreCase("Set-Cookie")) {
				for (String cookie : header.getValue()) {
					for (String cookieVariable : cookie.split("; ")) {
						String[] keyValue = cookieVariable.split("=");
						if (keyValue.length == 2) {
							if (keyValue[0].equals(fieldname)) {
								return Optional.ofNullable(keyValue[1]);
							}
						}
					}
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * Returns information about the given {@link MyUser}.
	 * 
	 * @param user the {@link MyUser} to get information
	 * @return the {@link Partner}
	 * @throws OpenemsException on error
	 */
	public Map<String, Object> getUserInformation(MyUser user) throws OpenemsNamedException {
		int partnerId = this.getOdooPartnerId(user);

		Map<String, Object> odooPartner = OdooUtils.readOne(this.credentials, Field.Partner.ODOO_MODEL, partnerId,
				Field.Partner.FIRSTNAME, //
				Field.Partner.LASTNAME, //
				Field.Partner.EMAIL, //
				Field.Partner.PHONE, //
				Field.Partner.STREET, //
				Field.Partner.ZIP, //
				Field.Partner.CITY, //
				Field.Partner.COUNTRY, //
				Field.Partner.COMPANY_NAME);

		return odooPartner;
	}

	/**
	 * Update the given {@link MyUser} with information from {@link JsonObject}.
	 * 
	 * @param user     the {@link MyUser} to update
	 * @param userJson the {@link JsonObject} information to update
	 * @throws OpenemsException on error
	 */
	public void setUserInformation(MyUser user, JsonObject userJson) throws OpenemsNamedException {
		Map<String, Object> fieldValues = new HashMap<>();
		fieldValues.putAll(this.updateAddress(userJson));
		fieldValues.putAll(this.updateCompany(user, userJson));

		JsonUtils.getAsOptionalString(userJson, "firstname") //
				.ifPresent(firstname -> fieldValues.put(Field.Partner.FIRSTNAME.id(), firstname));
		JsonUtils.getAsOptionalString(userJson, "lastname") //
				.ifPresent(lastname -> fieldValues.put(Field.Partner.LASTNAME.id(), lastname));
		JsonUtils.getAsOptionalString(userJson, "email") //
				.ifPresent(email -> fieldValues.put(Field.Partner.EMAIL.id(), email));
		JsonUtils.getAsOptionalString(userJson, "phone") //
				.ifPresent(phone -> fieldValues.put(Field.Partner.PHONE.id(), phone));

		int odooPartnerId = this.getOdooPartnerId(user.getOdooId());
		OdooUtils.write(this.credentials, Field.Partner.ODOO_MODEL, new Integer[] { odooPartnerId }, fieldValues);
	}

	/**
	 * Get address to update for an Odoo user.
	 * 
	 * @param addressJson {@link JsonObject} to get the fields to update
	 * @return Fields to update
	 * @throws OpenemsException on error
	 */
	private Map<String, Object> updateAddress(JsonObject addressJson) throws OpenemsException {
		Optional<JsonObject> optAddress = JsonUtils.getAsOptionalJsonObject(addressJson, "address");
		if (!optAddress.isPresent()) {
			return new HashMap<>();
		}
		JsonObject address = optAddress.get();

		Map<String, Object> addressFields = new HashMap<>();
		addressFields.put("type", "private");
		JsonUtils.getAsOptionalString(address, "street") //
				.ifPresent(street -> addressFields.put(Field.Partner.STREET.id(), street));
		JsonUtils.getAsOptionalString(address, "zip") //
				.ifPresent(zip -> addressFields.put(Field.Partner.ZIP.id(), zip));
		JsonUtils.getAsOptionalString(address, "city") //
				.ifPresent(city -> addressFields.put(Field.Partner.CITY.id(), city));

		Optional<String> optCountry = JsonUtils.getAsOptionalString(address, "country");
		if (optCountry.isPresent()) {
			String country = optCountry.get();

			int[] countryFound = OdooUtils.search(this.credentials, Field.Country.ODOO_MODEL, //
					new Domain(Field.Country.NAME, "=", country));
			if (countryFound.length == 1) {
				addressFields.put(Field.Partner.COUNTRY.id(), countryFound[0]);
			} else {
				int createdCountryId = OdooUtils.create(this.credentials, Field.Country.ODOO_MODEL, //
						new FieldValue<>(Field.Country.NAME, country));
				addressFields.put(Field.Partner.COUNTRY.id(), createdCountryId);
			}
		}

		return addressFields;
	}

	/**
	 * Get company to update for an Odoo user. Checks if the given company exits in
	 * Odoo and assign the company to the Odoo user. Otherwise a new company will be
	 * created in Odoo.
	 * 
	 * @param companyJson {@link JsonObject} to get the fields to update
	 * @return Fields to update
	 * @throws OpenemsException on error
	 */
	private Map<String, Object> updateCompany(JsonObject companyJson) throws OpenemsException {
		return this.updateCompany(null, companyJson);
	}

	/**
	 * Get company to update for an Odoo user. If given user is not null, check the
	 * users company with the new company name for equality. Both are equal nothing
	 * to update. Otherwise the new company will be assigned to the user or the new
	 * company will be created in Odoo.
	 * 
	 * @param user        {@link MyUser} to check company name
	 * @param companyJson {@link JsonObject} to get the fields to update
	 * @return Fields to update
	 * @throws OpenemsException on error
	 */
	private Map<String, Object> updateCompany(MyUser user, JsonObject companyJson) throws OpenemsException {
		Optional<JsonObject> optCompany = JsonUtils.getAsOptionalJsonObject(companyJson, "company");
		if (!optCompany.isPresent()) {
			return new HashMap<>();
		}
		Optional<String> optCompanyName = JsonUtils.getAsOptionalString(optCompany.get(), "name");
		if (!optCompanyName.isPresent()) {
			return new HashMap<>();
		}
		String jsonCompanyName = optCompanyName.get();

		if (user != null) {
			Map<String, Object> odooPartner = OdooUtils.readOne(this.credentials, Field.Partner.ODOO_MODEL, //
					this.getOdooPartnerId(user.getOdooId()), //
					Field.Partner.COMPANY_NAME);

			Optional<String> optPartnerCompanyName = ObjectUtils
					.getAsOptionalString(odooPartner.get(Field.Partner.COMPANY_NAME.id()));
			if (optPartnerCompanyName.isPresent()) {
				if (jsonCompanyName.equals(optPartnerCompanyName.get())) {
					return new HashMap<>();
				}
			}
		}

		int[] companyFound = OdooUtils.search(this.credentials, Field.Partner.ODOO_MODEL, //
				new Domain(Field.Partner.IS_COMPANY, "=", true),
				new Domain(Field.Partner.COMPANY_NAME, "=", jsonCompanyName));

		Map<String, Object> companyFields = new HashMap<>();
		if (companyFound.length > 0) {
			companyFields.put(Field.Partner.PARENT.id(), companyFound[0]);
		} else {
			int createdCompany = OdooUtils.create(this.credentials, Field.Partner.ODOO_MODEL, //
					new FieldValue<>(Field.Partner.IS_COMPANY, true),
					new FieldValue<>(Field.Partner.NAME, jsonCompanyName));
			companyFields.put(Field.Partner.PARENT.id(), createdCompany);
		}

		return companyFields;
	}

	/**
	 * Save the installation assistant protocol to Odoo.
	 * 
	 * @param user     {@link MyUser} current user
	 * @param protocol {@link SetupProtocol} the setup protocol
	 * @throws OpenemsNamedException
	 */
	public int submitSetupProtocol(MyUser user, JsonObject setupProtocolJson) throws OpenemsNamedException {
		JsonObject userJson = JsonUtils.getAsJsonObject(setupProtocolJson, "customer");
		JsonObject femsJson = JsonUtils.getAsJsonObject(setupProtocolJson, "fems");

		String femsId = JsonUtils.getAsString(femsJson, "id");
		int[] foundFems = OdooUtils.search(this.credentials, Field.EdgeDevice.ODOO_MODEL,
				new Domain(Field.EdgeDevice.NAME, "=", femsId));
		if (foundFems.length != 1) {
			throw new OpenemsException("FEMS not found for id [" + femsId + "]");
		}

		String password = PasswordUtils.generateRandomPassword(24);
		int odooUserId = this.createOdooUser(userJson, password);

		int customerId = this.getOdooPartnerId(odooUserId);
		int installerId = this.getOdooPartnerId(user);
		this.assignEdgeToUser(odooUserId, foundFems[0], OdooUserRole.OWNER);

		return this.createSetupProtocol(setupProtocolJson, foundFems[0], customerId, installerId);
	}

	/**
	 * Create an Odoo user and return thats id. If user already exists the user will
	 * be updated and return the user id.
	 * 
	 * @param partner  the {@link Partner} to create user
	 * @param password the password to set for the new user
	 * @return the Odoo user id
	 * @throws OpenemsNamedException
	 */
	private int createOdooUser(JsonObject userJson, String password) throws OpenemsNamedException {
		String email = JsonUtils.getAsString(userJson, "email");

		Map<String, Object> customerFields = new HashMap<>();
		customerFields.putAll(this.updateAddress(userJson));
		customerFields.putAll(this.updateCompany(userJson));

		JsonUtils.getAsOptionalString(userJson, "firstname") //
				.ifPresent(firstname -> customerFields.put(Field.Partner.FIRSTNAME.id(), firstname));
		JsonUtils.getAsOptionalString(userJson, "lastname") //
				.ifPresent(lastname -> customerFields.put(Field.Partner.LASTNAME.id(), lastname));
		JsonUtils.getAsOptionalString(userJson, "email") //
				.ifPresent(mail -> customerFields.put(Field.Partner.EMAIL.id(), mail));
		JsonUtils.getAsOptionalString(userJson, "phone") //
				.ifPresent(phone -> customerFields.put(Field.Partner.PHONE.id(), phone));

		int[] userFound = OdooUtils.search(this.credentials, Field.User.ODOO_MODEL,
				new Domain(Field.User.LOGIN, "=", email));

		if (userFound.length == 1) {
			int userId = userFound[0];
			OdooUtils.write(this.credentials, Field.User.ODOO_MODEL, new Integer[] { userId }, customerFields);
			return userId;
		} else {
			customerFields.put(Field.User.LOGIN.id(), email);
			customerFields.put(Field.User.PASSWORD.id(), password);
			customerFields.put(Field.User.GLOBAL_ROLE.id(), OdooUserRole.OWNER.getOdooRole());
			customerFields.put(Field.User.GROUPS.id(), Arrays.asList(OdooUserGroup.CUSTOMER.getGroupId()));
			return OdooUtils.create(this.credentials, Field.User.ODOO_MODEL, customerFields);
		}
	}

	/**
	 * Create a setup protocol in Odoo.
	 * 
	 * @param protocol    {@link SetupProtocol} to create
	 * @param customerId  Odoo customer id to set
	 * @param installerId Odoo installer id to set
	 * @param
	 * @return the Odoo id of created setup protocol
	 * @throws OpenemsException on error
	 */
	private int createSetupProtocol(JsonObject jsonObject, int femsId, int customerId, int installerId)
			throws OpenemsException {
		Integer locationId = null;

		Optional<JsonObject> jsonLocation = JsonUtils.getAsOptionalJsonObject(jsonObject, "location");
		if (jsonLocation.isPresent()) {
			JsonObject location = jsonLocation.get();

			Map<String, Object> locationFields = new HashMap<>();
			locationFields.putAll(this.updateAddress(location));
			locationFields.putAll(this.updateCompany(location));

			JsonUtils.getAsOptionalString(location, "firstname") //
					.ifPresent(firstname -> locationFields.put(Field.Partner.FIRSTNAME.id(), firstname));
			JsonUtils.getAsOptionalString(location, "lastname") //
					.ifPresent(lastname -> locationFields.put(Field.Partner.LASTNAME.id(), lastname));
			JsonUtils.getAsOptionalString(location, "email") //
					.ifPresent(mail -> locationFields.put(Field.Partner.EMAIL.id(), mail));
			JsonUtils.getAsOptionalString(location, "phone") //
					.ifPresent(phone -> locationFields.put(Field.Partner.PHONE.id(), phone));

			locationId = OdooUtils.create(this.credentials, Field.Partner.ODOO_MODEL, locationFields);
		}

		Map<String, Object> setupProtocolFields = new HashMap<>();
		setupProtocolFields.put(Field.SetupProtocol.CUSTOMER.id(), customerId);
		setupProtocolFields.put(Field.SetupProtocol.DIFFERENT_LOCATION.id(), locationId);
		setupProtocolFields.put(Field.SetupProtocol.INSTALLER.id(), installerId);
		setupProtocolFields.put(Field.SetupProtocol.FEMS.id(), femsId);

		int setupProtocolId = OdooUtils.create(this.credentials, Field.SetupProtocol.ODOO_MODEL, setupProtocolFields);

		Optional<JsonArray> lots = JsonUtils.getAsOptionalJsonArray(jsonObject, "lots");
		if (lots.isPresent()) {
			this.createSetupProtocolProductionLots(setupProtocolId, lots.get());
		}
		Optional<JsonArray> items = JsonUtils.getAsOptionalJsonArray(jsonObject, "items");
		if (items.isPresent()) {
			this.createSetupProtocolItems(setupProtocolId, items.get());
		}

		return setupProtocolId;
	}

	/**
	 * Create production lots for the given setup protocol id.
	 * 
	 * @param setupProtocolId assign to the lots
	 * @param lots            list of setup protocol production lots to create
	 * @throws OpenemsException on error
	 */
	private void createSetupProtocolProductionLots(int setupProtocolId, JsonArray lots) throws OpenemsException {
		for (int i = 0; i < lots.size(); i++) {
			JsonElement lot = lots.get(i);

			Map<String, Object> lotFields = new HashMap<>();
			lotFields.put(Field.SetupProtocolProductionLot.SETUP_PROTOCOL.id(), setupProtocolId);
			lotFields.put(Field.SetupProtocolProductionLot.SEQUENCE.id(), i);

			JsonUtils.getAsOptionalString(lot, "category") //
					.ifPresent(category -> lotFields.put("category", category));
			JsonUtils.getAsOptionalString(lot, "name") //
					.ifPresent(name -> lotFields.put("name", name));

			Optional<String> optSerialNumber = JsonUtils.getAsOptionalString(lot, "serialNumber");
			if (optSerialNumber.isPresent()) {
				int[] lotId = OdooUtils.search(this.credentials, Field.StockProductionLot.ODOO_MODEL, //
						new Domain(Field.StockProductionLot.SERIAL_NUMBER, "=", optSerialNumber.get()));

				if (lotId.length > 0) {
					lotFields.put(Field.SetupProtocolProductionLot.LOT.id(), lotId[0]);
				}
			}

			OdooUtils.create(this.credentials, Field.SetupProtocolProductionLot.ODOO_MODEL, lotFields);
		}
	}

	/**
	 * Create items for the given setup protocol id.
	 * 
	 * @param setupProtocolId assign to the items
	 * @param items           list of setup protocol items to create
	 * @throws OpenemsException on error
	 */
	private void createSetupProtocolItems(int setupProtocolId, JsonArray items) throws OpenemsException {
		for (int i = 0; i < items.size(); i++) {
			JsonElement item = items.get(i);

			Map<String, Object> setupProtocolItem = new HashMap<>();
			setupProtocolItem.put(Field.SetupProtocolItem.SETUP_PROTOCOL.id(), setupProtocolId);
			setupProtocolItem.put(Field.SetupProtocolItem.SEQUENCE.id(), i);

			JsonUtils.getAsOptionalString(item, "category") //
					.ifPresent(category -> setupProtocolItem.put("category", category));
			JsonUtils.getAsOptionalString(item, "name") //
					.ifPresent(name -> setupProtocolItem.put("name", name));
			JsonUtils.getAsOptionalString(item, "value") //
					.ifPresent(value -> setupProtocolItem.put("value", value));

			OdooUtils.create(this.credentials, Field.SetupProtocolItem.ODOO_MODEL, setupProtocolItem);
		}
	}

	/**
	 * Gets the referenced Odoo partner id for an Odoo user.
	 * 
	 * @param user the Odoo user
	 * @return the Odoo partner id
	 * @throws OpenemsException on error
	 */
	private int getOdooPartnerId(MyUser user) throws OpenemsException {
		return this.getOdooPartnerId(user.getOdooId());
	}

	/**
	 * Gets the referenced Odoo partner id for an Odoo user id.
	 * 
	 * @param odooUserId of the Odoo user
	 * @return the Odoo partner id
	 * @throws OpenemsException on error
	 */
	private int getOdooPartnerId(int odooUserId) throws OpenemsException {
		Map<String, Object> odooUser = OdooUtils.readOne(this.credentials, Field.User.ODOO_MODEL, odooUserId,
				Field.User.PARTNER);

		Optional<Integer> odooPartnerId = OdooUtils.getOdooRefernceId(odooUser.get(Field.User.PARTNER.id()));

		if (!odooPartnerId.isPresent()) {
			throw new OpenemsException("Odoo partner not found for user ['" + odooUserId + "']");
		}

		return odooPartnerId.get();
	}

	/**
	 * Register an user in Odoo with the given {@link OdooUserRole}.
	 * 
	 * @param jsonObject {@link JsonObject} that represents an user
	 * @param role       {@link OdooUserRole} to set for the user
	 * @throws OpenemsException on error
	 */
	public void registerUser(JsonObject jsonObject, OdooUserRole role) throws OpenemsException {
		Optional<String> optEmail = JsonUtils.getAsOptionalString(jsonObject, "email");
		if (!optEmail.isPresent()) {
			throw new OpenemsException("No email specified");
		}
		String email = optEmail.get();

		int[] userFound = OdooUtils.search(this.credentials, Field.User.ODOO_MODEL, //
				new Domain(Field.User.LOGIN, "=", email));
		if (userFound.length > 0) {
			throw new OpenemsException("User already exists with email [" + email + "]");
		}

		Map<String, Object> userFields = new HashMap<>();
		userFields.put(Field.User.LOGIN.id(), email);
		userFields.put(Field.Partner.EMAIL.id(), email);
		userFields.put(Field.User.GLOBAL_ROLE.id(), role.getOdooRole());
		userFields.putAll(this.updateAddress(jsonObject));
		userFields.putAll(this.updateCompany(jsonObject));

		JsonUtils.getAsOptionalString(jsonObject, "firstname") //
				.ifPresent(firstname -> userFields.put("firstname", firstname));
		JsonUtils.getAsOptionalString(jsonObject, "lastname") //
				.ifPresent(lastname -> userFields.put("lastname", lastname));
		JsonUtils.getAsOptionalString(jsonObject, "phone") //
				.ifPresent(phone -> userFields.put("phone", phone));
		JsonUtils.getAsOptionalString(jsonObject, "password") //
				.ifPresent(password -> userFields.put("password", password));
		JsonUtils.getAsOptionalBoolean(jsonObject, "subscribeNewsletter") //
				.ifPresent(subscribeNewsletter -> userFields.put("fenecon_crm_newsletter", subscribeNewsletter));

		OdooUtils.create(this.credentials, Field.User.ODOO_MODEL, userFields);
	}

}
