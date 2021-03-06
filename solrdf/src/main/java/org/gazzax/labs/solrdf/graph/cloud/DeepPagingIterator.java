package org.gazzax.labs.solrdf.graph.cloud;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.gazzax.labs.solrdf.Field;
import org.gazzax.labs.solrdf.NTriples;
import org.gazzax.labs.solrdf.graph.GraphEventConsumer;

import com.google.common.collect.UnmodifiableIterator;
import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.Triple;

/**
 * An iterator over SOLR results that uses the built-in Deep Paging strategy.
 * Internally it uses other iterators to represents each iteration state. 
 * 
 * @see http://solr.pl/en/2014/03/10/solr-4-7-efficient-deep-paging
 * @see http://heliosearch.org/solr/paging-and-deep-paging
 * @see <a href="http://en.wikipedia.org/wiki/Finite-state_machine">http://en.wikipedia.org/wiki/Finite-state_machine</a>
 * @author Andrea Gazzarini
 * @since 1.0
 */
public class DeepPagingIterator extends UnmodifiableIterator<Triple> {
	protected static final Set<String> TRIPLE_FIELDS = new HashSet<String>();
	static {
		TRIPLE_FIELDS.add(Field.S);
		TRIPLE_FIELDS.add(Field.P);
		TRIPLE_FIELDS.add(Field.O);
	}

	protected static final Triple DUMMY_TRIPLE = new Triple(Node.ANY, Node.ANY, Node.ANY);
	
	final SolrServer cloud; 
	final SolrQuery query;
	final GraphEventConsumer consumer;
	private SolrDocumentList page;
	
	private String nextCursorMark;
	private String sentCursorMark;

	/**
	 * Iteration state: we need to (re)execute a query. 
	 * This could be needed the very first time we start iteration and each time the current result
	 * page has been consumed.
	 */
	private final Iterator<Triple> firstQueryExecution = new UnmodifiableIterator<Triple>() {
		@Override
		public boolean hasNext() {
			try {
				final QueryResponse response = cloud.query(query);
				
				// FIXME
//			    consumer.onDocSet(result.getDocListAndSet().docSet);
//			    queryCommand.clearFlags(SolrIndexSearcher.GET_DOCSET);
			    
				sentCursorMark = query.get("cursorMark");
				nextCursorMark = response.getNextCursorMark();
				
				page = response.getResults();

				return page.size() > 0;
			} catch (final Exception exception) {
				throw new RuntimeException(exception);
			}
		}

		@Override
		public Triple next() {
			currentState = iterateOverCurrentPage;
			return currentState.next();
		}
	};

	/**
	 * Iteration state: we need to (re)execute a query. 
	 * This could be needed the very first time we start iteration and each time the current result
	 * page has been consumed.
	 */
	private final Iterator<Triple> executeQuery = new UnmodifiableIterator<Triple>() {
		@Override
		public boolean hasNext() {
			try {
				final QueryResponse response = cloud.query(query);
			    
				sentCursorMark = query.get("cursorMark");
				nextCursorMark = response.getNextCursorMark();
				
				page = response.getResults();

				return page.size() > 0;
			} catch (final Exception exception) {
				throw new RuntimeException(exception);
			}
		}

		@Override
		public Triple next() {
			currentState = iterateOverCurrentPage;
			return currentState.next();
		}
	};
			
	/**
	 * Iteration state: query has been executed and now it's time to iterate over results. 
	 */
	private final Iterator<Triple> iterateOverCurrentPage = new UnmodifiableIterator<Triple>() {
		Iterator<SolrDocument> iterator;
		
		@Override
		public boolean hasNext() {
			if (iterator().hasNext()) {
				return true;
			} else {
				iterator = null;
				currentState = checkForConsumptionCompleteness;
				return currentState.hasNext();
			}
		}
		
		@Override
		public Triple next() {
			final SolrDocument document = iterator().next();
			
			Triple triple = null;
			if (consumer.requireTripleBuild()) { 
				triple = Triple.create(
						NTriples.asURIorBlankNode((String) document.getFieldValue(Field.S)), 
						NTriples.asURI((String) document.getFieldValue(Field.P)),
						NTriples.asNode((String) document.getFieldValue(Field.O)));
			} else {
				triple = DUMMY_TRIPLE;
			}
			// FIXME
			// consumer.afterTripleHasBeenBuilt(triple, nextDocId);
			return triple;
		}
		
		Iterator<SolrDocument> iterator() {
			if (iterator == null) {
				iterator = page.iterator();	
			}
			return iterator;	 
		}
	};

	/**
	 * Iteration state: once a page has been consumed we need to determine if another query should be issued or not. 
	 */
	private final Iterator<Triple> checkForConsumptionCompleteness = new UnmodifiableIterator<Triple>() {
		@Override
		public boolean hasNext() {
			final boolean hasNext = !sentCursorMark.equals(nextCursorMark);
			if (hasNext) {
				query.set("cursorMark", nextCursorMark);
				currentState = executeQuery;
				return currentState.hasNext();
			}
			return false;
		}

		@Override
		public Triple next() {
			return currentState.next();
		}
	};
	
	private Iterator<Triple> currentState = firstQueryExecution;
	
	/**
	 * Builds a new iterator with the given data.
	 * 
	 * @param searcher the Solr index searcher.
	 * @param queryCommand the query command that will be submitted.static 
	 * @param sort the sort specs.
	 * @param consumer the Graph event consumer that will be notified on relevant events.
	 */
	DeepPagingIterator(
			final SolrServer cloud, 
			final SolrQuery query, 
			final GraphEventConsumer consumer) {
		this.cloud = cloud;
		this.query = query;
		this.sentCursorMark = "*";
		this.query.set("cursorMark", sentCursorMark);
		this.consumer = consumer;
	}

	@Override
	public boolean hasNext() {
		return currentState.hasNext();
	}

	@Override
	public Triple next() {
		return currentState.next();
	}
}