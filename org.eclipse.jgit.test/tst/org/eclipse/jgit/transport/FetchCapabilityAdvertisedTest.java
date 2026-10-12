/*
 * Copyright (C) 2026, BigBrassBand and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.transport;

import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_FILTER;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_OFS_DELTA;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;

import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevBlob;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link FetchConnection#isFetchCapabilityAdvertised(String)} on
 * connections that speak protocol V0 or read no advertisement at all. The V2
 * cases are in {@code FetchCapabilityAdvertisedHttpTest}, because the
 * in-process transport never negotiates V2.
 */
public class FetchCapabilityAdvertisedTest {
	private static final String MASTER = Constants.R_HEADS + Constants.MASTER;

	private final Object ctx = new Object();

	private InMemoryRepository server;

	private InMemoryRepository client;

	private TestRepository<InMemoryRepository> remote;

	private TestProtocol<Object> testProtocol;

	private URIish uri;

	private RevBlob blob;

	private RevCommit commit;

	@Before
	public void setUp() throws Exception {
		server = newRepo("server");
		client = newRepo("client");
		remote = new TestRepository<>(server);

		blob = remote.blob("foobar");
		commit = remote.commit(remote.tree(remote.file("1", blob)));
		remote.update(MASTER, commit);

		testProtocol = new TestProtocol<>(
				(Object req, Repository db) -> new UploadPack(db), null);
		uri = testProtocol.register(ctx, server);
	}

	@After
	public void tearDown() {
		Transport.unregister(testProtocol);
		remote.close();
	}

	private static InMemoryRepository newRepo(String name) {
		return new InMemoryRepository(new DfsRepositoryDescription(name));
	}

	private static void assertProtocolV0(FetchConnection c) {
		assertEquals(TransferConfig.ProtocolVersion.V0,
				((BasePackConnection) c).getProtocolVersion());
	}

	private void fetchMaster(FetchConnection c) throws TransportException {
		c.fetch(NullProgressMonitor.INSTANCE,
				Collections.singleton(c.getRef(MASTER)),
				Collections.emptySet());
	}

	// A V0 server with uploadpack.allowfilter lists filter in its capability
	// advertisement, and a filtered fetch on that connection is then accepted.
	@Test
	public void testFilterAdvertisedWhenServerAllowsFilter() throws Exception {
		server.getConfig().setBoolean("uploadpack", null, "allowfilter",
				true);

		try (Transport tn = testProtocol.open(uri, client, "server")) {
			tn.setFilterSpec(FilterSpec.withBlobLimit(0));
			try (FetchConnection c = tn.openFetch()) {
				assertProtocolV0(c);
				assertTrue(c.isFetchCapabilityAdvertised(OPTION_FILTER));

				fetchMaster(c);
			}
		}
		assertTrue(client.getObjectDatabase().has(commit));
		assertFalse(client.getObjectDatabase().has(blob));
	}

	// Without uploadpack.allowfilter the server does not advertise filter, and
	// the answer must match the filtered fetch's own pre-check, which refuses.
	@Test
	public void testFilterNotAdvertisedWhenServerDoesNotAllowFilter()
			throws Exception {
		try (Transport tn = testProtocol.open(uri, client, "server")) {
			tn.setFilterSpec(FilterSpec.withBlobLimit(0));
			try (FetchConnection c = tn.openFetch()) {
				assertProtocolV0(c);
				assertFalse(c.isFetchCapabilityAdvertised(OPTION_FILTER));

				TransportException e = assertThrows(TransportException.class,
						() -> fetchMaster(c));
				assertThat(e.getMessage(), containsString(
						JGitText.get().filterRequiresCapability));
			}
		}
	}

	// Under V0 every advertised capability is a fetch capability, including
	// ones V2 makes implicit; an upload-pack always advertises ofs-delta.
	@Test
	public void testTopLevelCapabilityCountsUnderV0() throws Exception {
		try (Transport tn = testProtocol.open(uri, client, "server");
				FetchConnection c = tn.openFetch()) {
			assertProtocolV0(c);
			assertTrue(c.isFetchCapabilityAdvertised(OPTION_OFS_DELTA));
		}
	}

	// A bundle carries no capability advertisement, so it advertises nothing
	// for fetching, filter included.
	@Test
	public void testBundleConnectionAdvertisesNothing() throws Exception {
		ByteArrayOutputStream bundle = new ByteArrayOutputStream();
		BundleWriter bw = new BundleWriter(server);
		bw.include(MASTER, commit);
		bw.writeBundle(NullProgressMonitor.INSTANCE, bundle);

		try (Transport tn = new TransportBundleStream(client,
				new URIish("in-memory://"),
				new ByteArrayInputStream(bundle.toByteArray()));
				FetchConnection c = tn.openFetch()) {
			assertFalse(c.isFetchCapabilityAdvertised(OPTION_FILTER));
			assertFalse(c.isFetchCapabilityAdvertised(OPTION_OFS_DELTA));
		}
	}
}
