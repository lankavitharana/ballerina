package org.ballerinalang.channels;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.ballerinalang.config.ConfigRegistry;
import org.ballerinalang.model.types.BType;
import org.ballerinalang.model.types.TypeTags;
import org.ballerinalang.model.util.JsonParser;
import org.ballerinalang.model.util.XMLUtils;
import org.ballerinalang.model.values.BBoolean;
import org.ballerinalang.model.values.BByte;
import org.ballerinalang.model.values.BFloat;
import org.ballerinalang.model.values.BInteger;
import org.ballerinalang.model.values.BString;
import org.ballerinalang.model.values.BValue;
import org.ballerinalang.util.exceptions.BallerinaException;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;

/**
 * Utility methods for storing/fetching channel messages.
 */
public class DatabaseUtils {

    private static HikariDataSource hikariDataSource;
    private static Connection con;
    private static HikariConfig config;
    private static ConfigRegistry registry = ConfigRegistry.getInstance();
    private static final String H2_MEM_URL = "jdbc:h2:mem:" + ChannelConstants.DB_NAME;

    private static void createDBConnection() throws SQLException {
        if (hikariDataSource == null) {
            config = new HikariConfig();
            String jdbcUrl = getJDBCURL();
            config.setJdbcUrl(jdbcUrl);
            setCredentials();
            hikariDataSource = new HikariDataSource(config);
            if (jdbcUrl.contains(H2_MEM_URL)) {
                con = hikariDataSource.getConnection();
                con.prepareStatement(ChannelConstants.CREATE).execute();
            }
        }
    }

    public static void addEntry(String channelName, BValue key, BValue value, BType keyType, BType valType) {

        try {
            createDBConnection();
            con = hikariDataSource.getConnection();
            PreparedStatement stmt = con.prepareStatement(ChannelConstants.INSERT);
            stmt.setString(1, channelName);
            if (keyType != null) {
                setParam(stmt, key, keyType, 2);
            } else {
                stmt.setNull(2, Types.VARCHAR);
            }

            setParam(stmt, value, valType, 3);
            stmt.execute();
        } catch (SQLException e) {
            throw new BallerinaException("error in get connection to persist channel message " + e.getMessage(),
                    e);
        } finally {
            try {
                if (con != null) {
                    con.close();
                }
            } catch (SQLException e) {
                //ignore
            }
        }
    }

    public static BValue getMessage(String channelName, BValue key, BType keyType, BType receiverType) {
        ResultSet result;
        try {
            createDBConnection();
            con = hikariDataSource.getConnection();
            PreparedStatement prpStmt;

            if (keyType != null) {
                prpStmt = con.prepareStatement(ChannelConstants.SELECT);
                setParam(prpStmt, key, keyType, 2);
            } else {
                prpStmt = con.prepareStatement(ChannelConstants.SELECT_NULL);
            }

            prpStmt.setString(1, channelName);
            result = prpStmt.executeQuery();
            if (result.next()) {
                int msgId = result.getInt(1);
                BValue value = getValue(result, receiverType);
                PreparedStatement dropStmt = con.prepareStatement(ChannelConstants.DROP);
                dropStmt.setInt(1, msgId);
                dropStmt.execute();
                return value;
            }
        } catch (SQLException e) {
            throw new BallerinaException("error retrieving channel message" + e.getMessage(),
                    e);
        } finally {
            try {
                if (con != null) {
                    con.close();
                }
            } catch (SQLException e) {
                //ignore
            }
        }
        return null;
    }

    private static BValue getValue(ResultSet resultSet, BType bType) throws SQLException {
        int type = bType.getTag();

        switch (type) {
            case TypeTags.INT_TAG:
                return new BInteger(resultSet.getLong(2));
            case TypeTags.STRING_TAG:
                return new BString(resultSet.getString(2));
            case TypeTags.BYTE_TAG:
                return new BByte(resultSet.getByte(2));
            case TypeTags.FLOAT_TAG:
                return new BFloat(resultSet.getDouble(2));
            case TypeTags.BOOLEAN_TAG:
                return new BBoolean(resultSet.getBoolean(2));
            case TypeTags.XML_TAG:
                return XMLUtils.parse(resultSet.getString(2));
            case TypeTags.JSON_TAG:
                return JsonParser.parse(resultSet.getString(2));
            default:
                throw new BallerinaException("unsupported data type " + type + ", for channel data");
        }
    }

    private static void setCredentials() {
        if (registry.contains(ChannelConstants.CONF_NAMESPACE + ChannelConstants.CONF_PASSWORD)) {
            config.setPassword(registry.getAsString(ChannelConstants.CONF_NAMESPACE +
                    ChannelConstants.CONF_PASSWORD));
        }

        if (registry.contains(ChannelConstants.CONF_NAMESPACE + ChannelConstants.CONF_USERNAME)) {
            config.setUsername(registry.getAsString(ChannelConstants.CONF_NAMESPACE +
                    ChannelConstants.CONF_USERNAME));
        }

    }

    private static void setParam(PreparedStatement stmt, BValue value, BType bType, int index) throws SQLException {

        int type = bType.getTag();

        switch (type) {
            case TypeTags.INT_TAG:
                stmt.setLong(index, ((BInteger) value).intValue());
                break;
            case TypeTags.STRING_TAG:
                stmt.setString(index, value.stringValue());
                break;
            case TypeTags.FLOAT_TAG:
                stmt.setDouble(index, ((BFloat) value).floatValue());
                break;
            case TypeTags.BOOLEAN_TAG:
                stmt.setBoolean(index, ((BBoolean) value).booleanValue());
                break;
            case TypeTags.BYTE_TAG:
                stmt.setByte(index, ((BByte) value).byteValue());
                break;
            default:
                stmt.setString(index, value.toString());
        }
    }

    private static String getJDBCURL() {

        String dbType = registry.getAsString(ChannelConstants.CONF_NAMESPACE + ChannelConstants.CONF_DB_TYPE);
        String hostOrPath = registry.getAsString(ChannelConstants.CONF_NAMESPACE +
                ChannelConstants.CONF_HOST_OR_PATH);
        long port = -1;
        if (registry.contains(ChannelConstants.CONF_NAMESPACE + ChannelConstants.CONF_PORT)) {
            port = registry.getAsInt(ChannelConstants.CONF_NAMESPACE + ChannelConstants.CONF_PORT);
        }
        String dbName = registry.getAsString(ChannelConstants.CONF_NAMESPACE + ChannelConstants.CONF_DB_NAME);
        String userName = registry.getAsString(ChannelConstants.CONF_NAMESPACE + ChannelConstants.CONF_USERNAME);
        String password = registry.getAsString(ChannelConstants.CONF_NAMESPACE + ChannelConstants.CONF_PASSWORD);
        String dbOptions = registry.getAsString(ChannelConstants.CONF_NAMESPACE + ChannelConstants.CONF_DB_OPTIONS);

        StringBuilder jdbcUrl = new StringBuilder();
        if (dbType == null) {
            config.setPassword(ChannelConstants.DB_PASSWORD);
            config.setUsername(ChannelConstants.DB_USERNAME);
            return H2_MEM_URL;
        }
        dbType = dbType.toUpperCase(Locale.ENGLISH);
        if (hostOrPath != null) {
            hostOrPath = hostOrPath.replaceAll("/$", "");
        }

        switch (dbType) {
            case ChannelConstants.DBTypes.MYSQL:
                if (port <= 0) {
                    port = ChannelConstants.DefaultPort.MYSQL;
                }
                jdbcUrl.append("jdbc:mysql://").append(hostOrPath).append(":").append(port).append("/").append(dbName);
                break;
            case ChannelConstants.DBTypes.SQLSERVER:
                if (port <= 0) {
                    port = ChannelConstants.DefaultPort.SQLSERVER;
                }
                jdbcUrl.append("jdbc:sqlserver://").append(hostOrPath).append(":").append(port).append(";databaseName=")
                        .append(dbName);
                break;
            case ChannelConstants.DBTypes.ORACLE:
                if (port <= 0) {
                    port = ChannelConstants.DefaultPort.ORACLE;
                }
                jdbcUrl.append("jdbc:oracle:thin:").append(userName).append("/").append(password).append("@")
                        .append(hostOrPath).append(":").append(port).append("/").append(dbName);
                break;
            case ChannelConstants.DBTypes.SYBASE:
                if (port <= 0) {
                    port = ChannelConstants.DefaultPort.SYBASE;
                }
                jdbcUrl.append("jdbc:sybase:Tds:").append(hostOrPath).append(":").append(port).append("/")
                        .append(dbName);
                break;
            case ChannelConstants.DBTypes.POSTGRESQL:
                if (port <= 0) {
                    port = ChannelConstants.DefaultPort.POSTGRES;
                }
                jdbcUrl.append("jdbc:postgresql://").append(hostOrPath).append(":").append(port).append("/")
                        .append(dbName);
                break;
            case ChannelConstants.DBTypes.IBMDB2:
                if (port <= 0) {
                    port = ChannelConstants.DefaultPort.IBMDB2;
                }
                jdbcUrl.append("jdbc:db2:").append(hostOrPath).append(":").append(port).append("/").append(dbName);
                break;
            case ChannelConstants.DBTypes.HSQLDB_SERVER:
                if (port <= 0) {
                    port = ChannelConstants.DefaultPort.HSQLDB_SERVER;
                }
                jdbcUrl.append("jdbc:hsqldb:hsql://").append(hostOrPath).append(":").append(port).append("/")
                        .append(dbName);
                break;
            case ChannelConstants.DBTypes.HSQLDB_FILE:
                jdbcUrl.append("jdbc:hsqldb:file:").append(hostOrPath).append(File.separator).append(dbName);
                break;
            case ChannelConstants.DBTypes.H2_SERVER:
                if (port <= 0) {
                    port = ChannelConstants.DefaultPort.H2_SERVER;
                }
                jdbcUrl.append("jdbc:h2:tcp:").append(hostOrPath).append(":").append(port).append("/").append(dbName);
                break;
            case ChannelConstants.DBTypes.H2_FILE:
                jdbcUrl.append("jdbc:h2:file:").append(hostOrPath).append(File.separator).append(dbName);
                break;
            case ChannelConstants.DBTypes.H2_MEMORY:
                jdbcUrl.append("jdbc:h2:mem:").append(dbName);
                break;
            case ChannelConstants.DBTypes.DERBY_SERVER:
                if (port <= 0) {
                    port = ChannelConstants.DefaultPort.DERBY_SERVER;
                }
                jdbcUrl.append("jdbc:derby:").append(hostOrPath).append(":").append(port).append("/").append(dbName);
                break;
            case ChannelConstants.DBTypes.DERBY_FILE:
                jdbcUrl.append("jdbc:derby:").append(hostOrPath).append(File.separator).append(dbName);
                break;
            default:
                throw new BallerinaException("cannot generate url for unknown database type : " + dbType);
        }

        return dbOptions == null ? jdbcUrl.toString() : jdbcUrl.append(dbOptions).toString();
    }
}
