package gator.mail.keycloak;

import java.sql.*;
import java.util.*;
import org.keycloak.util.JsonSerialization;

/** Calls only the privileged canonical identity contracts, never arbitrary SQL. */
final class GatorIdentityAdminSql {
    interface Transport { Map<String, Object> call(String function, Map<String, Object> input); }

    static Map<String, Object> call(String function, Map<String, Object> input) {
        if (!"app_fn_identity_admin".equals(function))
            throw new IllegalArgumentException("Unknown identity contract");
        try (Connection connection = DriverManager.getConnection(required("GATOR_IDP_JDBC_URL"),
                required("GATOR_IDP_JDBC_USER"), required("GATOR_IDP_JDBC_PASSWORD"))) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("select " + function + "(?)")) {
                statement.setString(1, JsonSerialization.writeValueAsString(input));
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Missing identity administration response");
                    Map<String, Object> result = response(rows.getString(1));
                    connection.commit();
                    return result;
                }
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (Rejected denied) { throw denied;
        } catch (Exception failure) {
            // SQL text and exception details may include private profile/credential data.
            throw new IllegalStateException("Identity administration storage unavailable");
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> response(String value) {
        try {
            Map<?, ?> wrapper = JsonSerialization.readValue(value, Map.class);
            if (wrapper.get("responses") instanceof List<?> list && list.size() == 1
                    && list.getFirst() instanceof Map<?, ?> entry) {
                if ("0".equals(entry.get("resNum")) && entry.get("response") instanceof Map<?, ?> response)
                    return (Map<String,Object>) response;
                String code=Objects.toString(entry.get("resNum"),"invalid-response");
                throw new Rejected(code.matches("[A-Z0-9]{1,12}")?code:"invalid-response");
            }
        } catch (Rejected denied) { throw denied;
        } catch (Exception invalid) { }
        throw new Rejected("invalid-response");
    }

    static final class Rejected extends IllegalStateException {
        final String code;
        Rejected(String code) { super("Identity administration rejected ("+code+")");this.code=code; }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
