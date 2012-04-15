package aQute.lib.deployer.repository.parsers;

import java.io.File;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.osgi.service.indexer.Builder;
import org.osgi.service.indexer.Capability;
import org.osgi.service.indexer.Namespaces;
import org.osgi.service.indexer.Requirement;
import org.osgi.service.indexer.ResourceAnalyzer;
import org.osgi.service.indexer.ResourceIndexer;
import org.osgi.service.indexer.impl.BIndex2;
import org.osgi.service.log.LogService;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import aQute.bnd.service.Registry;
import aQute.lib.collections.MultiMap;
import aQute.lib.deployer.repository.api.BaseResource;
import aQute.lib.deployer.repository.api.IRepositoryContentProvider;
import aQute.lib.deployer.repository.api.IRepositoryListener;
import aQute.lib.deployer.repository.api.Referral;
import aQute.lib.deployer.repository.api.StopParseException;

public class R5RepoContentProvider implements IRepositoryContentProvider {
	
	private static final String INDEX_NAME_COMPRESSED = "index.xml.gz";
	private static final String INDEX_NAME_PRETTY = "index.xml";

	public String getName() {
		return "R5";
	}
	
	public String getDefaultIndexName(boolean pretty) {
		return pretty ? INDEX_NAME_PRETTY : INDEX_NAME_COMPRESSED;
	}
	
	public ContentHandler createContentHandler(String baseUrl, IRepositoryListener listener) {
		return new R5RepoSaxHandler(baseUrl, listener);
	}
	
	public void generateIndex(Set<File> files, OutputStream output, String repoName, String rootUrl, boolean pretty, Registry registry, LogService log) throws Exception {
		BIndex2 indexer = new BIndex2();
		if (log != null) indexer.setLog(log);
		
		if (registry != null) {
			List<ResourceAnalyzer> analyzers = registry.getPlugins(ResourceAnalyzer.class);
			for (ResourceAnalyzer analyzer : analyzers) {
				// TODO: where to get the filter property??
				indexer.addAnalyzer(analyzer, null);
			}
		}
		
		Map<String, String> config = new HashMap<String, String>();
		config.put(ResourceIndexer.REPOSITORY_NAME, repoName);
		config.put(ResourceIndexer.ROOT_URL, rootUrl);
		config.put(ResourceIndexer.PRETTY, Boolean.toString(pretty));
		
		indexer.index(files, output, config);
	}
}

class R5RepoSaxHandler extends DefaultHandler {
	
	private static final String TAG_RESOURCE = "resource";
	
	private static final String TAG_REFERRAL = "referral";
	private static final String ATTR_REFERRAL_URL = "url";
	private static final String ATTR_REFERRAL_DEPTH = "depth";
	
	private static final String TAG_CAPABILITY = "capability";
	private static final String TAG_REQUIREMENT = "requirement";
	private static final String ATTR_NAMESPACE = "namespace";
	
	private static final String TAG_ATTRIBUTE = "attribute";
	private static final String TAG_DIRECTIVE = "directive";
	private static final String ATTR_NAME = "name";
	private static final String ATTR_VALUE = "value";
	private static final String ATTR_TYPE = "type";

	private final String baseUrl;
	private final IRepositoryListener resourceListener;
	
	private R5Resource.Builder resourceBuilder = null;
	private Builder capReqBuilder = null;
	
	private Referral referral = null;
	private int currentDepth;
	private int maxDepth;

	public R5RepoSaxHandler(String baseUrl, IRepositoryListener listener) {
		this.baseUrl = baseUrl;
		this.resourceListener = listener;
		this.currentDepth = 0;
	}
	
	public R5RepoSaxHandler(String baseUrl, IRepositoryListener listener, int maxDepth, int currentDepth) {
		this.baseUrl = baseUrl;
		this.resourceListener = listener;
		this.currentDepth = currentDepth;
	}
	
	@Override
	public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
		if (TAG_REFERRAL.equals(qName)) {
			referral = new Referral(atts.getValue(ATTR_REFERRAL_URL), parseInt(atts.getValue(ATTR_REFERRAL_DEPTH)));
		} else if (TAG_RESOURCE.equals(qName)) {
			resourceBuilder = new R5Resource.Builder().setBaseUrl(baseUrl);
		} else if (TAG_CAPABILITY.equals(qName) || TAG_REQUIREMENT.equals(qName)) {
			capReqBuilder = new Builder().setNamespace(atts.getValue(ATTR_NAMESPACE));
		} else if (TAG_ATTRIBUTE.equals(qName)) {
			String name = atts.getValue(ATTR_NAME);
			String valueStr = atts.getValue(ATTR_VALUE);
			String type = atts.getValue(ATTR_TYPE);
			capReqBuilder.addAttribute(name, convertAttribute(valueStr, type));
		} else if (TAG_DIRECTIVE.equals(qName)) {
			String name = atts.getValue(ATTR_NAME);
			String valueStr = atts.getValue(ATTR_VALUE);
			capReqBuilder.addDirective(name, valueStr);
		}
	}

	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		if (TAG_CAPABILITY.equals(qName)) {
			resourceBuilder.addCapability(capReqBuilder.buildCapability());
			capReqBuilder = null;
		} else if (TAG_REQUIREMENT.equals(qName)) {
			resourceBuilder.addRequirement(capReqBuilder.buildRequirement());
			capReqBuilder = null;
		} else if (TAG_RESOURCE.equals(qName)) {
			R5Resource resource = resourceBuilder.build();
			if (!resourceListener.processResource(resource))
				throw new StopParseException();
			resourceBuilder = null;
		} else if (TAG_REFERRAL.equals(qName)) {
			if (maxDepth == 0) {
				maxDepth = referral.getDepth();
			}
			resourceListener.processReferral(baseUrl, referral, maxDepth, currentDepth + 1);
			referral = null;
		}
	}

	private Object convertAttribute(String value, String type) {
		// TODO just treat everything as String for now
		return value;
	}
	
	private static int parseInt(String value) {
		if (value == null || "".equals(value))
			return 0;
		return Integer.parseInt(value);
	}

}

class R5Resource extends BaseResource {
	
	private final MultiMap<String, Capability> capabilities = new MultiMap<String, Capability>();
	private final MultiMap<String, Requirement> requires = new MultiMap<String, Requirement>();

	private R5Resource(String baseUrl, Collection<? extends Capability> capabilities, Collection<? extends Requirement> requires) {
		super(baseUrl);
		for (Capability capability : capabilities) {
			this.capabilities.add(capability.getNamespace(), capability);
		}
		for (Requirement requirement : requires) {
			this.requires.add(requirement.getNamespace(), requirement);
		}
	}
	
	public static class Builder {
		private String baseUrl = null;
		private final List<Capability> capabilities = new LinkedList<Capability>();
		private final List<Requirement> requires = new LinkedList<Requirement>();
		
		public Builder setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}
		public Builder addCapability(Capability capability) {
			this.capabilities.add(capability);
			return this;
		}
		public Builder addRequirement(Requirement require) {
			this.requires.add(require);
			return this;
		}
		
		public R5Resource build() {
			return new R5Resource(baseUrl, Collections.unmodifiableList(capabilities), Collections.unmodifiableList(requires));
		}
	}
	
	public List<Capability> getCapabilities(String namespace) {
		List<Capability> list = capabilities.get(namespace);
		return list != null ? Collections.unmodifiableList(list) : Collections.<Capability>emptyList();
	}

	public Capability findPackageCapability(String pkgName) {
		List<Capability> list = capabilities.get(Namespaces.NS_WIRING_PACKAGE);
		if (list != null) for (Capability capability : list) {
			if (pkgName.equals(capability.getAttributes().get(Namespaces.NS_WIRING_PACKAGE)))
				return capability;
		}
		return null;
	}
	
	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("Resource [capabilities=").append(capabilities)
				.append(", requirements=").append(requires)
				.append("]");
		return builder.toString();
	}

	@Override
	public String getIdentity() {
		String bsn = null;
		List<Capability> list = capabilities.get(Namespaces.NS_IDENTITY);
		if (list != null && !list.isEmpty()) {
			bsn = (String) list.get(0).getAttributes().get(Namespaces.NS_IDENTITY);
		}
		return bsn;
	}

	@Override
	public String getVersion() {
		String version = null;
		List<Capability> list = capabilities.get(Namespaces.NS_IDENTITY);
		if (list != null && !list.isEmpty()) {
			version = (String) list.get(0).getAttributes().get(Namespaces.ATTR_VERSION);
		}
		return version;
	}

	@Override
	public String getContentUrl() {
		String url = null;
		List<Capability> list = capabilities.get(Namespaces.NS_CONTENT);
		if (list != null && !list.isEmpty())
			url = (String) list.get(0).getAttributes().get(Namespaces.ATTR_CONTENT_URL);
		return url;
	}

}
