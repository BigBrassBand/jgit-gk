/*
 * Copyright (C) 2026, BigBrassBand and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.http.test;

import static org.eclipse.jgit.transport.GitProtocolConstants.CAPABILITY_REF_IN_WANT;
import static org.eclipse.jgit.transport.GitProtocolConstants.CAPABILITY_SERVER_OPTION;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_FILTER;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_OFS_DELTA;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_SHALLOW;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.junit.http.AccessEvent;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevBlob;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.FilterSpec;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link FetchConnection#isFetchCapabilityAdvertised(String)} over smart
 * HTTP, where the server negotiates either protocol V0 or V2.
 */
public class FetchCapabilityAdvertisedHttpTest
		extends AllProtocolsHttpTestCase {

	private Repository remoteRepository;

	private URIish remoteURI;

	private RevBlob blob;

	private RevCommit commit;

	public FetchCapabilityAdvertisedHttpTest(TestParameters params) {
		super(params);
	}

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();

		TestRepository<Repository> src = createTestRepository();
		String srcName = src.getRepository().getDirectory().getName();
		StoredConfig cfg = src.getRepository().getConfig();
		cfg.setInt("protocol", null, "version", enableProtocolV2 ? 2 : 0);
		cfg.save();

		GitServlet gs = new GitServlet();
		gs.setRepositoryResolver(new TestRepositoryResolver(src, srcName));
		ServletContextHandler app = server.addContext("/git");
		app.addServlet(new ServletHolder(gs), "/*");
		server.setUp();

		remoteRepository = src.getRepository();
		remoteURI = toURIish(app, srcName);

		blob = src.blob("foobar");
		commit = src.commit().add("1", blob).create();
		src.update(master, commit);
	}

	private void allowFilterOnServer() throws Exception {
		StoredConfig cfg = remoteRepository.getConfig();
		cfg.setBoolean("uploadpack", null, "allowfilter", true);
		cfg.save();
	}

	/**
	 * Fails unless the server answered with the protocol this run is
	 * parameterized for: under V2 the client lists refs with a separate
	 * ls-refs POST right after the advertisement, under V0 the advertisement
	 * GET carries the refs and nothing follows it.
	 */
	private void assertNegotiatedProtocol() {
		List<AccessEvent> requests = getRequests();
		assertEquals(enableProtocolV2 ? 2 : 1, requests.size());
		assertEquals("GET", requests.get(0).getMethod());
		if (enableProtocolV2) {
			AccessEvent lsRefs = requests.get(1);
			assertEquals("POST", lsRefs.getMethod());
			assertEquals(join(remoteURI, "git-upload-pack"), lsRefs.getPath());
			assertEquals("version=2", lsRefs.getRequestHeader("Git-Protocol"));
		}
	}

	private static void fetchMaster(FetchConnection c)
			throws TransportException {
		c.fetch(NullProgressMonitor.INSTANCE,
				Collections.singleton(c.getRef(master)),
				Collections.emptySet());
	}

	// With uploadpack.allowfilter the server offers filter (V0: in the
	// capability advertisement, V2: in the fetch line), and a filtered fetch
	// on that connection is then accepted.
	@Test
	public void testFilterAdvertisedWhenServerAllowsFilter() throws Exception {
		allowFilterOnServer();

		try (Repository dst = createBareRepository();
				Transport t = Transport.open(dst, remoteURI)) {
			t.setFilterSpec(FilterSpec.fromFilterLine("blob:limit=0"));
			try (FetchConnection c = t.openFetch()) {
				assertNegotiatedProtocol();
				assertTrue(c.isFetchCapabilityAdvertised(OPTION_FILTER));

				fetchMaster(c);
			}
			assertTrue(dst.getObjectDatabase().has(commit));
			assertFalse(dst.getObjectDatabase().has(blob));
		}
	}

	// Without uploadpack.allowfilter the server does not offer filter, and the
	// answer must match the filtered fetch's own pre-check, which refuses.
	@Test
	public void testFilterNotAdvertisedWhenServerDoesNotAllowFilter()
			throws Exception {
		try (Repository dst = createBareRepository();
				Transport t = Transport.open(dst, remoteURI)) {
			t.setFilterSpec(FilterSpec.fromFilterLine("blob:limit=0"));
			try (FetchConnection c = t.openFetch()) {
				assertNegotiatedProtocol();
				assertFalse(c.isFetchCapabilityAdvertised(OPTION_FILTER));

				TransportException e = assertThrows(TransportException.class,
						() -> fetchMaster(c));
				assertThat(e.getMessage(), containsString(
						JGitText.get().filterRequiresCapability));
			}
		}
	}

	// Under V2 only the fetch line names fetch capabilities: ofs-delta is
	// implicit there and server-option is a top-level capability, so neither
	// counts, while shallow is in the fetch line. A capability is a whole
	// token of that line, so "want" is not advertised by "ref-in-want". Under
	// V0 every advertised capability counts.
	@Test
	public void testFetchLineVersusTopLevelCapabilities() throws Exception {
		StoredConfig cfg = remoteRepository.getConfig();
		cfg.setBoolean("uploadpack", null, "allowrefinwant", true);
		cfg.save();

		try (Repository dst = createBareRepository();
				Transport t = Transport.open(dst, remoteURI);
				FetchConnection c = t.openFetch()) {
			assertNegotiatedProtocol();
			assertTrue(c.isFetchCapabilityAdvertised(OPTION_SHALLOW));
			if (enableProtocolV2) {
				assertFalse(c.isFetchCapabilityAdvertised(OPTION_OFS_DELTA));
				assertFalse(
						c.isFetchCapabilityAdvertised(CAPABILITY_SERVER_OPTION));
				assertTrue(
						c.isFetchCapabilityAdvertised(CAPABILITY_REF_IN_WANT));
				assertFalse(c.isFetchCapabilityAdvertised("want"));
			} else {
				assertTrue(c.isFetchCapabilityAdvertised(OPTION_OFS_DELTA));
			}
		}
	}
}
