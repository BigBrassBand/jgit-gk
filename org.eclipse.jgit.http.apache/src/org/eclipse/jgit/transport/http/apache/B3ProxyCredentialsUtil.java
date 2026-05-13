package org.eclipse.jgit.transport.http.apache;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCredentialsProvider;

import java.net.Authenticator;

/**
 * Created by isvirkina on 28.07.17.
 */
public class B3ProxyCredentialsUtil {

    /**
     * @param httpProtocol httpProtocol
     * @return Returns String
     */
    public static String getHttpProxyHost(String httpProtocol) {
        return System.getProperty(httpProtocol + ".proxyHost");
    }

    /**
     * @param httpProtocol httpProtocol
     * @return Returns Integer
     */
    public static Integer getHttpProxyPort(String httpProtocol) {
        String port = System.getProperty(httpProtocol + ".proxyPort");
        if (port != null) {
            return Integer.valueOf(port);
        }
        return null;
    }

    /**
     * @param httpProtocol httpProtocol
     * @return Returns String
     */
    public static String getHttpProxyLogin(String httpProtocol) {
        return System.getProperty(httpProtocol + ".proxyUser");
    }

    /**
     * @param httpProtocol httpProtocol
     * @return Returns String
     */
    public static String getHttpProxyPassword(String httpProtocol) {
        return System.getProperty(httpProtocol + ".proxyPassword");
    }

    /**
     * Restores defaults
     */
    public void restoreToDefault() {
        Authenticator.setDefault(null);
    }

    /**
     * @param context context
     * @param protocol protocol
     */
    public static void setProxyIfNeeded(HttpClientContext context, String protocol) {
        if (protocol == null) {
            return;
        }

        protocol = protocol.toLowerCase();
        String httpHost = getHttpProxyHost(protocol);
        Integer httpPort = getHttpProxyPort(protocol);

        String httpLogin = getHttpProxyLogin(protocol);
        String httpPassword = getHttpProxyPassword(protocol);

        boolean httpProxyCredentialsSpecified = (httpHost != null && httpPort != null && httpLogin != null && httpPassword != null);

        if (httpProxyCredentialsSpecified) {

            CredentialsProvider cProvider = context.getCredentialsProvider() != null
                    ? context.getCredentialsProvider()
                    : new BasicCredentialsProvider();

            cProvider.setCredentials(new AuthScope(httpHost, httpPort), new UsernamePasswordCredentials(httpLogin, httpPassword));

            context.setCredentialsProvider(cProvider);
        }
    }
}
