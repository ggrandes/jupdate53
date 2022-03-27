package org.javastack.jupdate53;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.ChangeStatus;
import software.amazon.awssdk.services.route53.model.Route53Exception;

public class Servlet53 extends HttpServlet {
	private static final long serialVersionUID = 53L;
	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
	private static final long UPDATE_FRESH_MILLIS = TimeUnit.SECONDS.toMillis(30);
	private static final String SYSPROP_CONFIG_PATH;
	private static final String WHITELIST_FILENAME;

	private static final Pattern ZONEID_INVALID_REGEX;
	private static final Pattern FQDN_INVALID_REGEX;
	private static final Pattern TTL_INVALID_REGEX;
	private static final Pattern IPV4_INVALID_REGEX;
	private static final Pattern IPV4_VALID_REGEX;

	private final Properties whiteList = new Properties();
	private final HashMap<String, Long> lastUpdateTS = new HashMap<>();
	private final HashMap<String, String> lastUpdateValue = new HashMap<>();

	private Route53Client r53cli = null;

	static {
		final String packageName = MethodHandles.lookup().lookupClass().getPackage().getName();
		SYSPROP_CONFIG_PATH = packageName + ".config.path";
		WHITELIST_FILENAME = packageName + ".whitelist.properties";
		ZONEID_INVALID_REGEX = Pattern.compile("[^a-zA-Z0-9]");
		TTL_INVALID_REGEX = Pattern.compile("[^0-9]");
		FQDN_INVALID_REGEX = Pattern.compile("[^a-zA-Z0-9.-]");
		IPV4_INVALID_REGEX = Pattern.compile("[^0-9.]");
		// Yeah, you need one of these:
		// https://extendsclass.com/regex-tester.html
		// https://regex101.com
		// https://regexr.com
		final String ipv4regExp = "^(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]\\d|\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]\\d|\\d)$";
		IPV4_VALID_REGEX = Pattern.compile(ipv4regExp);
	}

	public void init(final ServletConfig config) //
			throws ServletException {
		final File configPath = new File(System.getProperty(SYSPROP_CONFIG_PATH, "/etc/"));
		final File whiteListFile = new File(configPath, WHITELIST_FILENAME);
		log.info("SystemProperty {}: {}", SYSPROP_CONFIG_PATH, configPath.getAbsolutePath());
		log.info("Trying to read whitelist from: {}", whiteListFile.getAbsolutePath());
		try (final InputStream is = new FileInputStream(whiteListFile)) {
			whiteList.clear();
			whiteList.load(is);
			log.info("Whitelist items: {}", whiteList.size());
		} catch (Exception e) {
			log.error("Exception: {}", e.getMessage());
		}
		r53cli = Route53Client.builder().region(Region.AWS_GLOBAL).build();
	}

	public void destroy() {
		if (r53cli != null) {
			r53cli.close();
			r53cli = null;
		}
		whiteList.clear();
		synchronized (lastUpdateTS) {
			lastUpdateTS.clear();
		}
	}

	private String isAccepted(final String[] fqdn, final String ip, final String zoneId) {
		// Check WhiteList
		for (final String name : fqdn) {
			if (!whiteList.getProperty(name, "!FAIL").equals(zoneId)) {
				return "NOT_WHITELISTED";
			}
		}
		// Check lastUpdateTS
		synchronized (lastUpdateTS) {
			final long now = System.currentTimeMillis();
			final Long nowLong = Long.valueOf(now);
			for (final String name : fqdn) {
				Long lastTS = lastUpdateTS.get(name);
				if (lastTS == null) {
					lastTS = Long.valueOf(0);
				}
				if ((lastTS.longValue() + UPDATE_FRESH_MILLIS) > now) {
					return "TOO_FAST";
				}
				lastUpdateTS.put(name, nowLong);
			}
		}
		// Check lastUpdateValue
		synchronized (lastUpdateValue) {
			for (final String name : fqdn) {
				final String lastValue = lastUpdateValue.get(name);
				if ((lastValue != null) && ip.equals(lastValue)) {
					return "ALREADY_UPDATED";
				}
				lastUpdateValue.put(name, ip);
			}
		}
		return null;
	}

	private boolean isInvalidInputRegExp(final String in, final int maxLen, final Pattern invalidRegExp) {
		if ((in == null) || in.isEmpty() || (in.length() > maxLen)) {
			return true;
		}
		return invalidRegExp.matcher(in).find();
	}

	private String returnJson(final String status) {
		return "{ \"status\": \"" + status + "\" }";
	}

	protected void doGet(final HttpServletRequest request, final HttpServletResponse response) //
			throws ServletException, IOException {
		response.setHeader("Cache-Control", "private");
		response.setContentType("text/plain");
		response.setCharacterEncoding("ISO-8859-1");
		response.getWriter().append(request.getRemoteAddr());
	}

	protected void doPost(final HttpServletRequest request, final HttpServletResponse response) //
			throws ServletException, IOException {
		response.setHeader("Cache-Control", "private");
		response.setContentType("application/json");
		response.setCharacterEncoding("ISO-8859-1");
		// Parameter: zoneid (string)
		final String p_zoneId = request.getParameter("zoneid");
		if ((p_zoneId == null) || (p_zoneId.length() < 3)
				|| isInvalidInputRegExp(p_zoneId, 128, ZONEID_INVALID_REGEX)) {
			response.getWriter().append(returnJson("INVALID:ZONEID"));
			return;
		}
		// Parameter: fqdn (multiple string, fully qualified domain name)
		final String[] p_fqdn = request.getParameterValues("fqdn");
		if ((p_fqdn == null) || (p_fqdn.length < 1) || (p_fqdn.length > 42)) {
			response.getWriter().append(returnJson("INVALID:FQDN"));
			return;
		}
		for (int i = 0; i < p_fqdn.length; i++) {
			final String name = p_fqdn[i];
			if ((name == null) || (name.length() < 4)) {
				response.getWriter().append(returnJson("INVALID:FQDN"));
				return;
			}
			final String n = ((name.charAt(0) == '*') ? name.substring(1) : name);
			if (isInvalidInputRegExp(n, 255, FQDN_INVALID_REGEX)) {
				response.getWriter().append(returnJson("INVALID:FQDN"));
				return;
			}
			p_fqdn[i] = name.toLowerCase();
		}
		// Parameter: ttl (number)
		final long p_ttl;
		{
			String sttl = request.getParameter("ttl");
			if ((sttl == null) || sttl.isEmpty()) {
				sttl = "3"; // default
			} else if (isInvalidInputRegExp(sttl, 3, TTL_INVALID_REGEX)) {
				response.getWriter().append(returnJson("INVALID:TTL"));
				return;
			}
			p_ttl = Math.min(Math.max(Long.parseLong(sttl), 1), 600);
		}
		// Parameter: ip (string, ipv4)
		String p_ip = request.getParameter("ip");
		if ((p_ip == null) || p_ip.isEmpty()) {
			p_ip = request.getRemoteAddr();
		} else if (isInvalidInputRegExp(p_ip, 15, IPV4_INVALID_REGEX) //
				|| !IPV4_VALID_REGEX.matcher(p_ip).matches()) {
			response.getWriter().append(returnJson("INVALID:IP"));
			return;
		}
		// Check throttle
		final String isSkip = isAccepted(p_fqdn, p_ip, p_zoneId);
		if (isSkip != null) {
			response.getWriter().append(returnJson("SKIP:" + isSkip));
			return;
		}
		// Run baby, run!
		try {
			final ChangeStatus status = Update53.updateRecordTypeA(r53cli, //
					p_zoneId, Arrays.asList(p_fqdn), p_ttl, p_ip, false);
			response.getWriter().append(returnJson(status.name() + ":" + p_ip));
		} catch (Route53Exception e) {
			log.error("Route53Exception: {}", e.getMessage());
			response.getWriter().append(returnJson("ERROR:R53"));
		} catch (Throwable t) {
			log.error("Exception: {}", t.getMessage(), t);
			response.getWriter().append(returnJson("ERROR:EXCEPTION"));
		}
	}
}
