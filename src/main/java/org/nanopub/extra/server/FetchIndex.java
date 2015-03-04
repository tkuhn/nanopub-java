package org.nanopub.extra.server;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.trustyuri.TrustyUriUtils;

import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.nanopub.extra.index.IndexUtils;
import org.nanopub.extra.index.NanopubIndex;
import org.openrdf.model.URI;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandlerException;

public class FetchIndex {

	public static final int maxParallelRequestsPerServer = 5;

	private OutputStream out;
	private RDFFormat format;
	private boolean writeIndex, writeContent;
	private boolean running = false;
	private List<FetchNanopubTask> fetchTasks;
	private List<ServerInfo> servers;
	private Map<String,Set<FetchNanopubTask>> serverLoad;
	private int nanopubCount;
	private ProgressListener progressListener;

	public FetchIndex(String indexUri, OutputStream out, RDFFormat format, boolean writeIndex, boolean writeContent) {
		this.out = out;
		this.format = format;
		this.writeIndex = writeIndex;
		this.writeContent = writeContent;
		fetchTasks = new ArrayList<>();
		fetchTasks.add(new FetchNanopubTask(indexUri, true));
		servers = new ArrayList<>();
		serverLoad = new HashMap<>();
		ServerIterator serverIterator = new ServerIterator();
		while (serverIterator.hasNext()) {
			ServerInfo serverInfo = serverIterator.next();
			servers.add(serverInfo);
			serverLoad.put(serverInfo.getPublicUrl(), new HashSet<FetchNanopubTask>());
		}
		nanopubCount = 0;
	}

	public void run() {
		if (running) return;
		running = true;
		while (!fetchTasks.isEmpty()) {
			checkTasks();
		}
	}

	private void checkTasks() {
		int count = 0;
		for (FetchNanopubTask task : new ArrayList<>(fetchTasks)) {
			count++;
			if (task.isRunning()) continue;
			if (task.getLastServerUrl() != null) {
				serverLoad.get(task.getLastServerUrl()).remove(task);
			}
			if (task.getNanopub() == null) {
				if (task.getTriedServersCount() == servers.size()) {
					throw new RuntimeException("Nanopub not found: " + task.getNanopubUri());
				}
				List<ServerInfo> shuffledServers = new ArrayList<>(servers);
				Collections.shuffle(shuffledServers);
				for (ServerInfo serverInfo : shuffledServers) {
					String serverUrl = serverInfo.getPublicUrl();
					if (task.hasServerBeenTried(serverUrl)) continue;
					int load = serverLoad.get(serverUrl).size();
					if (load >= maxParallelRequestsPerServer) continue;
					assignTask(task, serverUrl);
					break;
				}
			} else if (count == 1) {
				Nanopub np = task.getNanopub();
				try {
					if (task.isIndex()) {
						if (!IndexUtils.isIndex(np)) {
							throw new RuntimeException("NOT AN INDEX: " + np.getUri());
						}
						NanopubIndex npi = IndexUtils.castToIndex(np);
						if (writeIndex) {
							writeNanopub(npi);
						}
						if (writeContent) {
							for (URI elementUri : npi.getElements()) {
								fetchTasks.add(new FetchNanopubTask(elementUri.toString(), false));
							}
						}
						for (URI subIndexUri : npi.getSubIndexes()) {
							fetchTasks.add(new FetchNanopubTask(subIndexUri.toString(), true));
						}
						if (npi.getAppendedIndex() != null) {
							fetchTasks.add(new FetchNanopubTask(npi.getAppendedIndex().toString(), true));
						}
					} else {
						writeNanopub(np);
					}
					fetchTasks.remove(0);
					count = 0;
				} catch (Exception ex) {
					throw new RuntimeException(ex);
				}
			}
		}
	}

	private void writeNanopub(Nanopub np) throws RDFHandlerException {
		nanopubCount++;
		if (progressListener != null && nanopubCount % 100 == 0) {
			progressListener.progress(nanopubCount);
		}
		NanopubUtils.writeToStream(np, out, format);
	}

	public int getNanopubCount() {
		return nanopubCount;
	}

	public void setProgressListener(ProgressListener l) {
		progressListener = l;
	}

	private void assignTask(final FetchNanopubTask task, final String serverUrl) {
		task.prepareForTryingServer(serverUrl);
		serverLoad.get(serverUrl).add(task);
		Runnable runFetchTask = new Runnable() {

			@Override
			public void run() {
				task.tryServer(serverUrl);
			}

		};
		Thread thread = new Thread(runFetchTask);
		thread.start();
	}

	private class FetchNanopubTask {

		private String npUri;
		private boolean isIndex;
		private Nanopub nanopub;
		private Set<String> servers = new HashSet<>();
		private boolean running = false;
		private String lastServerUrl;

		public FetchNanopubTask(String npUri, boolean isIndex) {
			this.npUri = npUri;
			this.isIndex = isIndex;
		}

		public boolean isIndex() {
			return isIndex;
		}

		public Nanopub getNanopub() {
			return nanopub;
		}

		public String getNanopubUri() {
			return npUri;
		}

		public boolean isRunning() {
			return running;
		}

		public boolean hasServerBeenTried(String serverUrl) {
			return servers.contains(serverUrl);
		}

		public int getTriedServersCount() {
			return servers.size();
		}

		public String getLastServerUrl() {
			return lastServerUrl;
		}

		public void prepareForTryingServer(String serverUrl) {
			servers.add(serverUrl);
			lastServerUrl = serverUrl;
			running = true;
		}

		public void tryServer(String serverUrl) {
			try {
				nanopub = GetNanopub.get(TrustyUriUtils.getArtifactCode(npUri), serverUrl);
			} catch (Exception ex) {
				// ignore
			} finally {
				running = false;
			}
		}

	}


	public static interface ProgressListener {

		public void progress(int count);

	}

}