/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.search.query;

import com.carrotsearch.randomizedtesting.RandomizedTest;

import java.io.IOException;
import java.util.Arrays;
import lombok.SneakyThrows;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.BulkScorer;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.opensearch.common.lucene.search.FilteredCollector;
import org.opensearch.common.lucene.search.TopDocsAndMaxScore;
import org.opensearch.index.mapper.MapperService;
import org.opensearch.index.mapper.TextFieldMapper;
import org.opensearch.index.query.BoostingQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.index.query.TermQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.ParsedQuery;
import org.opensearch.neuralsearch.query.HybridQuery;
import org.opensearch.neuralsearch.query.HybridQueryContext;
import org.opensearch.neuralsearch.query.HybridQueryWeight;
import org.opensearch.neuralsearch.query.OpenSearchQueryTestCase;
import org.opensearch.neuralsearch.search.collector.HybridTopScoreDocCollector;
import org.opensearch.neuralsearch.search.collector.PagingFieldCollector;
import org.opensearch.neuralsearch.search.collector.SimpleFieldCollector;
import org.opensearch.neuralsearch.search.query.exception.HybridSearchRescoreQueryException;
import org.opensearch.search.DocValueFormat;
import org.opensearch.search.internal.ContextIndexSearcher;
import org.opensearch.search.internal.ScrollContext;
import org.opensearch.search.internal.SearchContext;
import org.opensearch.search.query.QuerySearchResult;
import org.opensearch.search.query.ReduceableSearchResult;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.opensearch.neuralsearch.search.util.HybridSearchResultFormatUtil.MAGIC_NUMBER_DELIMITER;
import static org.opensearch.neuralsearch.search.util.HybridSearchResultFormatUtil.MAGIC_NUMBER_START_STOP;

import org.opensearch.search.rescore.QueryRescorerBuilder;
import org.opensearch.search.rescore.RescoreContext;
import org.opensearch.search.rescore.Rescorer;
import org.opensearch.search.rescore.RescorerBuilder;
import org.opensearch.search.sort.SortAndFormats;

public class HybridCollectorManagerTests extends OpenSearchQueryTestCase {

    private static final String TEXT_FIELD_NAME = "field";
    private static final String TEST_DOC_TEXT1 = "Hello world";
    private static final String TEST_DOC_TEXT2 = "Hi to this place";
    private static final String TEST_DOC_TEXT3 = "We would like to welcome everyone";
    private static final String QUERY1 = "hello";
    private static final String QUERY2 = "hi";
    protected static final String QUERY3 = "everyone";

    private Directory directory;
    private IndexWriter writer;
    private IndexReader indexReader;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        IndexObjects indexObjects = createIndexObjects(3);
        directory = indexObjects.directory();
        writer = indexObjects.writer();
        indexReader = indexObjects.indexReader();
    }

    @SneakyThrows
    public void testNewCollector_whenNotConcurrentSearch_thenSuccessful() {
        SearchContext searchContext = mock(SearchContext.class);
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        MapperService mapperService = createMapperService();
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) createMapperService().fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);
        TermQueryBuilder termSubQuery = QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY1);
        HybridQueryContext hybridQueryContext = HybridQueryContext.builder().paginationDepth(10).build();
        HybridQuery hybridQuery = new HybridQuery(List.of(termSubQuery.toQuery(mockQueryShardContext)), hybridQueryContext);

        when(searchContext.mapperService()).thenReturn(mapperService);
        when(searchContext.query()).thenReturn(hybridQuery);
        ContextIndexSearcher indexSearcher = mock(ContextIndexSearcher.class);
        when(indexSearcher.getIndexReader()).thenReturn(indexReader);
        when(searchContext.searcher()).thenReturn(indexSearcher);

        Map<Class<?>, CollectorManager<? extends Collector, ReduceableSearchResult>> classCollectorManagerMap = new HashMap<>();
        when(searchContext.queryCollectorManagers()).thenReturn(classCollectorManagerMap);
        when(searchContext.shouldUseConcurrentSearch()).thenReturn(false);

        CollectorManager hybridCollectorManager = HybridCollectorManager.createHybridCollectorManager(searchContext);
        assertNotNull(hybridCollectorManager);
        assertTrue(hybridCollectorManager instanceof HybridCollectorManager.HybridCollectorNonConcurrentManager);

        Collector collector = hybridCollectorManager.newCollector();
        assertNotNull(collector);
        assertTrue(collector instanceof HybridTopScoreDocCollector);

        Collector secondCollector = hybridCollectorManager.newCollector();
        assertSame(collector, secondCollector);
    }

    @SneakyThrows
    public void testNewCollector_whenConcurrentSearch_thenSuccessful() {
        SearchContext searchContext = mock(SearchContext.class);
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) createMapperService().fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);
        TermQueryBuilder termSubQuery = QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY1);
        HybridQueryContext hybridQueryContext = HybridQueryContext.builder().paginationDepth(10).build();
        HybridQuery hybridQuery = new HybridQuery(List.of(termSubQuery.toQuery(mockQueryShardContext)), hybridQueryContext);

        MapperService mapperService = createMapperService();
        when(searchContext.mapperService()).thenReturn(mapperService);
        when(searchContext.query()).thenReturn(hybridQuery);
        ContextIndexSearcher indexSearcher = mock(ContextIndexSearcher.class);
        when(indexSearcher.getIndexReader()).thenReturn(indexReader);
        when(searchContext.searcher()).thenReturn(indexSearcher);

        Map<Class<?>, CollectorManager<? extends Collector, ReduceableSearchResult>> classCollectorManagerMap = new HashMap<>();
        when(searchContext.queryCollectorManagers()).thenReturn(classCollectorManagerMap);
        when(searchContext.shouldUseConcurrentSearch()).thenReturn(true);

        CollectorManager hybridCollectorManager = HybridCollectorManager.createHybridCollectorManager(searchContext);
        assertNotNull(hybridCollectorManager);
        assertTrue(hybridCollectorManager instanceof HybridCollectorManager.HybridCollectorConcurrentSearchManager);

        Collector collector = hybridCollectorManager.newCollector();
        assertNotNull(collector);
        assertTrue(collector instanceof HybridTopScoreDocCollector);

        Collector secondCollector = hybridCollectorManager.newCollector();
        assertNotSame(collector, secondCollector);
    }

    @SneakyThrows
    public void testPostFilter_whenNotConcurrentSearch_thenSuccessful() {
        SearchContext searchContext = mock(SearchContext.class);
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) createMapperService().fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);
        TermQueryBuilder termSubQuery = QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY1);
        HybridQueryContext hybridQueryContext = HybridQueryContext.builder().paginationDepth(10).build();
        HybridQuery hybridQuery = new HybridQuery(List.of(termSubQuery.toQuery(mockQueryShardContext)), hybridQueryContext);

        MapperService mapperService = createMapperService();
        when(searchContext.mapperService()).thenReturn(mapperService);
        QueryBuilder postFilterQuery = QueryBuilders.termQuery(TEXT_FIELD_NAME, "world");
        ParsedQuery parsedQuery = new ParsedQuery(postFilterQuery.toQuery(mockQueryShardContext));
        searchContext.parsedQuery(parsedQuery);

        Query pfQuery = postFilterQuery.toQuery(mockQueryShardContext);
        when(searchContext.parsedPostFilter()).thenReturn(parsedQuery);

        when(searchContext.query()).thenReturn(hybridQuery);
        ContextIndexSearcher indexSearcher = mock(ContextIndexSearcher.class);
        when(indexSearcher.getIndexReader()).thenReturn(indexReader);
        when(searchContext.searcher()).thenReturn(indexSearcher);

        when(indexSearcher.rewrite(pfQuery)).thenReturn(pfQuery);
        Weight weight = mock(Weight.class);
        when(indexSearcher.createWeight(pfQuery, ScoreMode.COMPLETE_NO_SCORES, 1f)).thenReturn(weight);

        Map<Class<?>, CollectorManager<? extends Collector, ReduceableSearchResult>> classCollectorManagerMap = new HashMap<>();
        when(searchContext.queryCollectorManagers()).thenReturn(classCollectorManagerMap);
        when(searchContext.shouldUseConcurrentSearch()).thenReturn(false);

        CollectorManager hybridCollectorManager = HybridCollectorManager.createHybridCollectorManager(searchContext);
        assertNotNull(hybridCollectorManager);
        assertTrue(hybridCollectorManager instanceof HybridCollectorManager.HybridCollectorNonConcurrentManager);

        Collector collector = hybridCollectorManager.newCollector();
        assertNotNull(collector);
        assertTrue(collector instanceof FilteredCollector);
        assertTrue(((FilteredCollector) collector).getCollector() instanceof HybridTopScoreDocCollector);

        Collector secondCollector = hybridCollectorManager.newCollector();
        assertSame(collector, secondCollector);
        assertTrue(((FilteredCollector) secondCollector).getCollector() instanceof HybridTopScoreDocCollector);
    }

    @SneakyThrows
    public void testPostFilter_whenConcurrentSearch_thenSuccessful() {
        SearchContext searchContext = mock(SearchContext.class);
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) createMapperService().fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);
        TermQueryBuilder termSubQuery = QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY1);
        HybridQueryContext hybridQueryContext = HybridQueryContext.builder().paginationDepth(10).build();
        HybridQuery hybridQuery = new HybridQuery(List.of(termSubQuery.toQuery(mockQueryShardContext)), hybridQueryContext);

        MapperService mapperService = createMapperService();
        when(searchContext.mapperService()).thenReturn(mapperService);

        QueryBuilder postFilterQuery = QueryBuilders.termQuery(TEXT_FIELD_NAME, "world");
        Query pfQuery = postFilterQuery.toQuery(mockQueryShardContext);
        ParsedQuery parsedQuery = new ParsedQuery(pfQuery);
        searchContext.parsedQuery(parsedQuery);

        when(searchContext.parsedPostFilter()).thenReturn(parsedQuery);

        when(searchContext.query()).thenReturn(hybridQuery);
        ContextIndexSearcher indexSearcher = mock(ContextIndexSearcher.class);
        when(indexSearcher.getIndexReader()).thenReturn(indexReader);
        when(searchContext.searcher()).thenReturn(indexSearcher);

        when(indexSearcher.rewrite(pfQuery)).thenReturn(pfQuery);
        Weight weight = mock(Weight.class);
        when(indexSearcher.createWeight(pfQuery, ScoreMode.COMPLETE_NO_SCORES, 1f)).thenReturn(weight);

        Map<Class<?>, CollectorManager<? extends Collector, ReduceableSearchResult>> classCollectorManagerMap = new HashMap<>();
        when(searchContext.queryCollectorManagers()).thenReturn(classCollectorManagerMap);
        when(searchContext.shouldUseConcurrentSearch()).thenReturn(true);

        CollectorManager hybridCollectorManager = HybridCollectorManager.createHybridCollectorManager(searchContext);
        assertNotNull(hybridCollectorManager);
        assertTrue(hybridCollectorManager instanceof HybridCollectorManager.HybridCollectorConcurrentSearchManager);

        Collector collector = hybridCollectorManager.newCollector();
        assertNotNull(collector);
        assertTrue(collector instanceof FilteredCollector);
        assertTrue(((FilteredCollector) collector).getCollector() instanceof HybridTopScoreDocCollector);

        Collector secondCollector = hybridCollectorManager.newCollector();
        assertNotSame(collector, secondCollector);
        assertTrue(((FilteredCollector) secondCollector).getCollector() instanceof HybridTopScoreDocCollector);
    }

    @SneakyThrows
    public void testReduce_whenMatchedDocs_thenSuccessful() {
        SearchContext searchContext = mock(SearchContext.class);
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) createMapperService().fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);
        HybridQueryContext hybridQueryContext = HybridQueryContext.builder().paginationDepth(10).build();

        MapperService mapperService = createMapperService();
        when(searchContext.mapperService()).thenReturn(mapperService);

        HybridQuery hybridQueryWithTerm = new HybridQuery(
            List.of(QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY1).toQuery(mockQueryShardContext)),
            hybridQueryContext
        );
        when(searchContext.query()).thenReturn(hybridQueryWithTerm);
        ContextIndexSearcher indexSearcher = mock(ContextIndexSearcher.class);
        when(indexSearcher.getIndexReader()).thenReturn(indexReader);
        when(searchContext.searcher()).thenReturn(indexSearcher);
        when(searchContext.size()).thenReturn(1);

        Map<Class<?>, CollectorManager<? extends Collector, ReduceableSearchResult>> classCollectorManagerMap = new HashMap<>();
        when(searchContext.queryCollectorManagers()).thenReturn(classCollectorManagerMap);
        when(searchContext.shouldUseConcurrentSearch()).thenReturn(false);

        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);

        Directory directory = newDirectory();
        final IndexWriter w = new IndexWriter(directory, newIndexWriterConfig());
        FieldType ft = new FieldType(TextField.TYPE_NOT_STORED);
        ft.freeze();

        int docId1 = RandomizedTest.randomInt();
        int docId2 = RandomizedTest.randomInt();
        int docId3 = RandomizedTest.randomInt();
        w.addDocument(getDocument(TEXT_FIELD_NAME, docId1, TEST_DOC_TEXT1, ft));
        w.addDocument(getDocument(TEXT_FIELD_NAME, docId2, TEST_DOC_TEXT2, ft));
        w.addDocument(getDocument(TEXT_FIELD_NAME, docId3, TEST_DOC_TEXT3, ft));
        w.flush();
        w.commit();

        IndexReader reader = DirectoryReader.open(w);
        IndexSearcher searcher = new IndexSearcher(reader);

        CollectorManager hybridCollectorManager = HybridCollectorManager.createHybridCollectorManager(searchContext);
        HybridTopScoreDocCollector collector = (HybridTopScoreDocCollector) hybridCollectorManager.newCollector();

        QueryBuilder postFilterQuery = QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY1);

        Query pfQuery = postFilterQuery.toQuery(mockQueryShardContext);
        ParsedQuery parsedQuery = new ParsedQuery(pfQuery);
        searchContext.parsedQuery(parsedQuery);
        when(searchContext.parsedPostFilter()).thenReturn(parsedQuery);
        when(indexSearcher.rewrite(pfQuery)).thenReturn(pfQuery);
        Weight postFilterWeight = mock(Weight.class);
        when(indexSearcher.createWeight(pfQuery, ScoreMode.COMPLETE_NO_SCORES, 1f)).thenReturn(postFilterWeight);

        CollectorManager hybridCollectorManager1 = HybridCollectorManager.createHybridCollectorManager(searchContext);
        FilteredCollector collector1 = (FilteredCollector) hybridCollectorManager1.newCollector();

        Weight weight = new HybridQueryWeight(hybridQueryWithTerm, searcher, ScoreMode.TOP_SCORES, BoostingQueryBuilder.DEFAULT_BOOST);
        collector.setWeight(weight);
        collector1.setWeight(weight);
        LeafReaderContext leafReaderContext = searcher.getIndexReader().leaves().get(0);
        LeafCollector leafCollector = collector.getLeafCollector(leafReaderContext);
        LeafCollector leafCollector1 = collector1.getLeafCollector(leafReaderContext);
        BulkScorer scorer = weight.bulkScorer(leafReaderContext);
        scorer.score(leafCollector, leafReaderContext.reader().getLiveDocs(), 0, DocIdSetIterator.NO_MORE_DOCS);
        leafCollector.finish();
        scorer.score(leafCollector1, leafReaderContext.reader().getLiveDocs(), 0, DocIdSetIterator.NO_MORE_DOCS);
        leafCollector1.finish();

        Object results = hybridCollectorManager.reduce(List.of());
        Object results1 = hybridCollectorManager1.reduce(List.of());

        assertNotNull(results);
        assertNotNull(results1);
        ReduceableSearchResult reduceableSearchResult = ((ReduceableSearchResult) results);
        QuerySearchResult querySearchResult = new QuerySearchResult();
        reduceableSearchResult.reduce(querySearchResult);
        TopDocsAndMaxScore topDocsAndMaxScore = querySearchResult.topDocs();

        assertNotNull(topDocsAndMaxScore);
        assertEquals(1, topDocsAndMaxScore.topDocs.totalHits.value());
        assertEquals(TotalHits.Relation.EQUAL_TO, topDocsAndMaxScore.topDocs.totalHits.relation());
        float maxScore = topDocsAndMaxScore.maxScore;
        assertTrue(maxScore > 0);
        ScoreDoc[] scoreDocs = topDocsAndMaxScore.topDocs.scoreDocs;
        assertEquals(4, scoreDocs.length);
        assertEquals(MAGIC_NUMBER_START_STOP, scoreDocs[0].score, DELTA_FOR_ASSERTION);
        assertEquals(MAGIC_NUMBER_DELIMITER, scoreDocs[1].score, DELTA_FOR_ASSERTION);
        assertEquals(maxScore, scoreDocs[2].score, DELTA_FOR_ASSERTION);
        assertEquals(MAGIC_NUMBER_START_STOP, scoreDocs[3].score, DELTA_FOR_ASSERTION);

        w.close();
        reader.close();
        directory.close();
    }

    @SneakyThrows
    public void testNewCollector_whenNotConcurrentSearchAndSortingIsApplied_thenSuccessful() {
        SearchContext searchContext = mock(SearchContext.class);
        SortField sortField = new SortField("_doc", SortField.Type.DOC);
        Sort sort = new Sort(sortField);
        DocValueFormat docValueFormat[] = new DocValueFormat[] { DocValueFormat.RAW };
        when(searchContext.sort()).thenReturn(new SortAndFormats(sort, docValueFormat));
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) createMapperService().fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);
        TermQueryBuilder termSubQuery = QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY1);
        HybridQueryContext hybridQueryContext = HybridQueryContext.builder().paginationDepth(10).build();

        HybridQuery hybridQuery = new HybridQuery(List.of(termSubQuery.toQuery(mockQueryShardContext)), hybridQueryContext);

        when(searchContext.query()).thenReturn(hybridQuery);
        MapperService mapperService = createMapperService();
        when(searchContext.mapperService()).thenReturn(mapperService);
        ContextIndexSearcher indexSearcher = mock(ContextIndexSearcher.class);
        when(indexSearcher.getIndexReader()).thenReturn(indexReader);
        when(searchContext.searcher()).thenReturn(indexSearcher);

        Map<Class<?>, CollectorManager<? extends Collector, ReduceableSearchResult>> classCollectorManagerMap = new HashMap<>();
        when(searchContext.queryCollectorManagers()).thenReturn(classCollectorManagerMap);
        when(searchContext.shouldUseConcurrentSearch()).thenReturn(false);

        CollectorManager hybridCollectorManager = HybridCollectorManager.createHybridCollectorManager(searchContext);
        assertNotNull(hybridCollectorManager);
        assertTrue(hybridCollectorManager instanceof HybridCollectorManager.HybridCollectorNonConcurrentManager);

        Collector collector = hybridCollectorManager.newCollector();
        assertNotNull(collector);
        assertTrue(collector instanceof SimpleFieldCollector);

        Collector secondCollector = hybridCollectorManager.newCollector();
        assertSame(collector, secondCollector);
    }

    @SneakyThrows
    public void testNewCollector_whenNotConcurrentSearchAndSortingAndSearchAfterAreApplied_thenSuccessful() {
        SearchContext searchContext = mock(SearchContext.class);
        SortField sortField = new SortField("_doc", SortField.Type.DOC);
        Sort sort = new Sort(sortField);
        DocValueFormat docValueFormat[] = new DocValueFormat[] { DocValueFormat.RAW };
        FieldDoc after = new FieldDoc(Integer.MAX_VALUE, 0.0f, new Object[] { 1 }, -1);
        when(searchContext.sort()).thenReturn(new SortAndFormats(sort, docValueFormat));
        when(searchContext.searchAfter()).thenReturn(after);
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) createMapperService().fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);
        TermQueryBuilder termSubQuery = QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY1);
        HybridQueryContext hybridQueryContext = HybridQueryContext.builder().paginationDepth(10).build();

        HybridQuery hybridQuery = new HybridQuery(List.of(termSubQuery.toQuery(mockQueryShardContext)), hybridQueryContext);

        when(searchContext.query()).thenReturn(hybridQuery);
        MapperService mapperService = createMapperService();
        when(searchContext.mapperService()).thenReturn(mapperService);
        ContextIndexSearcher indexSearcher = mock(ContextIndexSearcher.class);
        when(indexSearcher.getIndexReader()).thenReturn(indexReader);
        when(searchContext.searcher()).thenReturn(indexSearcher);

        Map<Class<?>, CollectorManager<? extends Collector, ReduceableSearchResult>> classCollectorManagerMap = new HashMap<>();
        when(searchContext.queryCollectorManagers()).thenReturn(classCollectorManagerMap);
        when(searchContext.shouldUseConcurrentSearch()).thenReturn(false);

        CollectorManager hybridCollectorManager = HybridCollectorManager.createHybridCollectorManager(searchContext);
        assertNotNull(hybridCollectorManager);
        assertTrue(hybridCollectorManager instanceof HybridCollectorManager.HybridCollectorNonConcurrentManager);

        Collector collector = hybridCollectorManager.newCollector();
        assertNotNull(collector);
        assertTrue(collector instanceof PagingFieldCollector);

        Collector secondCollector = hybridCollectorManager.newCollector();
        assertSame(collector, secondCollector);
    }

    @SneakyThrows
    public void testReduce_whenMatchedDocsAndSortingIsApplied_thenSuccessful() {
        SearchContext searchContext = mock(SearchContext.class);
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) createMapperService().fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);
        HybridQueryContext hybridQueryContext = HybridQueryContext.builder().build();

        HybridQuery hybridQueryWithMatchAll = new HybridQuery(
            List.of(QueryBuilders.matchAllQuery().toQuery(mockQueryShardContext)),
            hybridQueryContext
        );
        when(searchContext.query()).thenReturn(hybridQueryWithMatchAll);
        ContextIndexSearcher indexSearcher = mock(ContextIndexSearcher.class);
        when(indexSearcher.getIndexReader()).thenReturn(indexReader);
        when(searchContext.searcher()).thenReturn(indexSearcher);
        when(searchContext.size()).thenReturn(1);

        MapperService mapperService = createMapperService();
        when(searchContext.mapperService()).thenReturn(mapperService);

        Map<Class<?>, CollectorManager<? extends Collector, ReduceableSearchResult>> classCollectorManagerMap = new HashMap<>();
        when(searchContext.queryCollectorManagers()).thenReturn(classCollectorManagerMap);
        when(searchContext.shouldUseConcurrentSearch()).thenReturn(false);
        SortField sortField = new SortField("_doc", SortField.Type.DOC);
        Sort sort = new Sort(sortField);
        DocValueFormat docValueFormat[] = new DocValueFormat[] { DocValueFormat.RAW };
        when(searchContext.sort()).thenReturn(new SortAndFormats(sort, docValueFormat));

        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);

        Directory directory = newDirectory();
        final IndexWriter w = new IndexWriter(directory, newIndexWriterConfig());
        FieldType ft = new FieldType(TextField.TYPE_NOT_STORED);
        ft.freeze();

        int docId1 = RandomizedTest.randomInt();
        int docId2 = RandomizedTest.randomInt();
        int docId3 = RandomizedTest.randomInt();
        w.addDocument(getDocument(TEXT_FIELD_NAME, docId1, TEST_DOC_TEXT1, ft));
        w.addDocument(getDocument(TEXT_FIELD_NAME, docId2, TEST_DOC_TEXT2, ft));
        w.addDocument(getDocument(TEXT_FIELD_NAME, docId3, TEST_DOC_TEXT3, ft));
        w.flush();
        w.commit();

        IndexReader reader = DirectoryReader.open(w);
        IndexSearcher searcher = new IndexSearcher(reader);

        CollectorManager hybridCollectorManager = HybridCollectorManager.createHybridCollectorManager(searchContext);
        SimpleFieldCollector simpleFieldCollector = (SimpleFieldCollector) hybridCollectorManager.newCollector();

        FieldDoc after = new FieldDoc(Integer.MAX_VALUE, 0.0f, new Object[] { docId1 }, -1);
        when(searchContext.searchAfter()).thenReturn(after);
        CollectorManager hybridCollectorManager1 = HybridCollectorManager.createHybridCollectorManager(searchContext);
        PagingFieldCollector pagingFieldCollector = (PagingFieldCollector) hybridCollectorManager1.newCollector();

        Weight weight = new HybridQueryWeight(hybridQueryWithMatchAll, searcher, ScoreMode.TOP_SCORES, BoostingQueryBuilder.DEFAULT_BOOST);
        simpleFieldCollector.setWeight(weight);
        pagingFieldCollector.setWeight(weight);
        LeafReaderContext leafReaderContext = searcher.getIndexReader().leaves().get(0);
        LeafCollector leafCollector = simpleFieldCollector.getLeafCollector(leafReaderContext);
        LeafCollector leafCollector1 = pagingFieldCollector.getLeafCollector(leafReaderContext);
        BulkScorer scorer = weight.bulkScorer(leafReaderContext);
        scorer.score(leafCollector, leafReaderContext.reader().getLiveDocs(), 0, DocIdSetIterator.NO_MORE_DOCS);
        leafCollector.finish();
        BulkScorer scorer1 = weight.bulkScorer(leafReaderContext);
        scorer1.score(leafCollector1, leafReaderContext.reader().getLiveDocs(), 0, DocIdSetIterator.NO_MORE_DOCS);
        leafCollector1.finish();

        Object results = hybridCollectorManager.reduce(List.of());
        Object results1 = hybridCollectorManager1.reduce(List.of());

        assertNotNull(results);
        assertNotNull(results1);
        ReduceableSearchResult reduceableSearchResult = ((ReduceableSearchResult) results);
        QuerySearchResult querySearchResult = new QuerySearchResult();
        reduceableSearchResult.reduce(querySearchResult);
        TopDocsAndMaxScore topDocsAndMaxScore = querySearchResult.topDocs();

        assertNotNull(topDocsAndMaxScore);
        assertEquals(3, topDocsAndMaxScore.topDocs.totalHits.value());
        assertEquals(TotalHits.Relation.EQUAL_TO, topDocsAndMaxScore.topDocs.totalHits.relation());
        float maxScore = topDocsAndMaxScore.maxScore;
        assertTrue(maxScore > 0);
        ScoreDoc[] scoreDocs = topDocsAndMaxScore.topDocs.scoreDocs;
        assertEquals(4, scoreDocs.length);
        assertEquals(MAGIC_NUMBER_START_STOP, scoreDocs[0].score, DELTA_FOR_ASSERTION);
        assertEquals(MAGIC_NUMBER_DELIMITER, scoreDocs[1].score, DELTA_FOR_ASSERTION);
        assertEquals(maxScore, scoreDocs[2].score, DELTA_FOR_ASSERTION);
        assertEquals(MAGIC_NUMBER_START_STOP, scoreDocs[3].score, DELTA_FOR_ASSERTION);

        w.close();
        reader.close();
        directory.close();
    }

    @SneakyThrows
    public void testReduceWithConcurrentSegmentSearch_whenMultipleCollectorsMatchedDocs_thenSuccessful() {
        SearchContext searchContext = mock(SearchContext.class);
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) createMapperService().fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);
        HybridQueryContext hybridQueryContext = HybridQueryContext.builder().paginationDepth(10).build();

        HybridQuery hybridQueryWithTerm = new HybridQuery(
            List.of(
                QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY1).toQuery(mockQueryShardContext),
                QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY2).toQuery(mockQueryShardContext)
            ),
            hybridQueryContext
        );
        when(searchContext.query()).thenReturn(hybridQueryWithTerm);
        MapperService mapperService = createMapperService();
        when(searchContext.mapperService()).thenReturn(mapperService);
        ContextIndexSearcher indexSearcher = mock(ContextIndexSearcher.class);
        when(indexSearcher.getIndexReader()).thenReturn(indexReader);
        when(searchContext.searcher()).thenReturn(indexSearcher);
        when(searchContext.size()).thenReturn(10);

        Map<Class<?>, CollectorManager<? extends Collector, ReduceableSearchResult>> classCollectorManagerMap = new HashMap<>();
        when(searchContext.queryCollectorManagers()).thenReturn(classCollectorManagerMap);
        when(searchContext.shouldUseConcurrentSearch()).thenReturn(true);

        Directory directory = newDirectory();
        IndexWriter w = new IndexWriter(directory, newIndexWriterConfig());
        FieldType ft = new FieldType(TextField.TYPE_NOT_STORED);
        ft.setIndexOptions(IndexOptions.DOCS_AND_FREQS);
        ft.setOmitNorms(false);
        ft.freeze();

        w.addDocument(getDocument(TEXT_FIELD_NAME, 1, TEST_DOC_TEXT1, ft));
        w.addDocument(getDocument(TEXT_FIELD_NAME, 2, TEST_DOC_TEXT2, ft));
        w.addDocument(getDocument(TEXT_FIELD_NAME, 3, TEST_DOC_TEXT3, ft));
        w.flush();
        w.commit();

        IndexReader reader = DirectoryReader.open(w);
        IndexSearcher searcher = new IndexSearcher(reader);

        CollectorManager hybridCollectorManager = HybridCollectorManager.createHybridCollectorManager(searchContext);
        HybridTopScoreDocCollector collector1 = (HybridTopScoreDocCollector) hybridCollectorManager.newCollector();
        HybridTopScoreDocCollector collector2 = (HybridTopScoreDocCollector) hybridCollectorManager.newCollector();

        Weight weight = new HybridQueryWeight(hybridQueryWithTerm, searcher, ScoreMode.TOP_SCORES, BoostingQueryBuilder.DEFAULT_BOOST);
        collector1.setWeight(weight);
        collector2.setWeight(weight);

        LeafReaderContext leafReaderContext = searcher.getIndexReader().leaves().get(0);
        LeafCollector leafCollector1 = collector1.getLeafCollector(leafReaderContext);
        LeafCollector leafCollector2 = collector2.getLeafCollector(leafReaderContext);

        BulkScorer scorer = weight.bulkScorer(leafReaderContext);
        scorer.score(leafCollector1, leafReaderContext.reader().getLiveDocs(), 0, DocIdSetIterator.NO_MORE_DOCS);
        scorer.score(leafCollector2, leafReaderContext.reader().getLiveDocs(), 0, DocIdSetIterator.NO_MORE_DOCS);

        leafCollector1.finish();
        leafCollector2.finish();

        Object results = hybridCollectorManager.reduce(List.of(collector1, collector2));

        assertNotNull(results);
        ReduceableSearchResult reduceableSearchResult = ((ReduceableSearchResult) results);
        QuerySearchResult querySearchResult = new QuerySearchResult();
        reduceableSearchResult.reduce(querySearchResult);
        TopDocsAndMaxScore topDocsAndMaxScore = querySearchResult.topDocs();

        assertNotNull(topDocsAndMaxScore);
        assertEquals(2, topDocsAndMaxScore.topDocs.totalHits.value());
        assertEquals(TotalHits.Relation.EQUAL_TO, topDocsAndMaxScore.topDocs.totalHits.relation());
        float maxScore = topDocsAndMaxScore.maxScore;
        assertTrue(maxScore > 0);

        ScoreDoc[] scoreDocs = topDocsAndMaxScore.topDocs.scoreDocs;
        assertEquals(6, scoreDocs.length);
        assertEquals(MAGIC_NUMBER_START_STOP, scoreDocs[0].score, DELTA_FOR_ASSERTION);
        assertEquals(MAGIC_NUMBER_DELIMITER, scoreDocs[1].score, DELTA_FOR_ASSERTION);
        assertTrue(scoreDocs[2].score > 0);
        assertEquals(MAGIC_NUMBER_DELIMITER, scoreDocs[3].score, DELTA_FOR_ASSERTION);
        assertTrue(scoreDocs[4].score > 0);
        assertEquals(MAGIC_NUMBER_START_STOP, scoreDocs[5].score, DELTA_FOR_ASSERTION);
        // we have to assert that one of hits is max score because scores are generated for each run and order is not guaranteed
        assertTrue(Float.compare(scoreDocs[2].score, maxScore) == 0 || Float.compare(scoreDocs[4].score, maxScore) == 0);

        w.close();
        reader.close();
        directory.close();
    }

    @SneakyThrows
    public void testReduceWithConcurrentSegmentSearch_whenMultipleCollectorsMatchedDocsWithSort_thenSuccessful() {
        SearchContext searchContext = mock(SearchContext.class);
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) createMapperService().fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);
        HybridQueryContext hybridQueryContext = HybridQueryContext.builder().build();

        HybridQuery hybridQueryWithTerm = new HybridQuery(
            List.of(QueryBuilders.matchAllQuery().toQuery(mockQueryShardContext)),
            hybridQueryContext
        );
        when(searchContext.query()).thenReturn(hybridQueryWithTerm);
        MapperService mapperService = createMapperService();
        when(searchContext.mapperService()).thenReturn(mapperService);
        ContextIndexSearcher indexSearcher = mock(ContextIndexSearcher.class);
        IndexObjects indexObjects = createIndexObjects(2);
        IndexReader indexReader = indexObjects.indexReader();
        when(indexSearcher.getIndexReader()).thenReturn(indexReader);
        when(searchContext.searcher()).thenReturn(indexSearcher);
        when(searchContext.size()).thenReturn(1);

        DocValueFormat docValueFormat[] = new DocValueFormat[] { DocValueFormat.RAW };
        SortField sortField = new SortField("id", SortField.Type.DOC);
        Sort sort = new Sort(sortField);
        SortAndFormats sortAndFormats = new SortAndFormats(sort, docValueFormat);
        when(searchContext.sort()).thenReturn(sortAndFormats);

        Map<Class<?>, CollectorManager<? extends Collector, ReduceableSearchResult>> classCollectorManagerMap = new HashMap<>();
        when(searchContext.queryCollectorManagers()).thenReturn(classCollectorManagerMap);
        when(searchContext.shouldUseConcurrentSearch()).thenReturn(true);

        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);

        Directory directory = newDirectory();
        final IndexWriter w = new IndexWriter(directory, newIndexWriterConfig());
        FieldType ft = new FieldType(TextField.TYPE_NOT_STORED);
        ft.freeze();

        int docId1 = RandomizedTest.randomInt();
        int docId2 = RandomizedTest.randomInt();
        int docId3 = RandomizedTest.randomInt();
        int[] docIds = new int[] { docId1, docId2, docId3 };
        Arrays.sort(docIds);

        w.addDocument(getDocument(TEXT_FIELD_NAME, docIds[0], TEST_DOC_TEXT1, ft));
        w.addDocument(getDocument(TEXT_FIELD_NAME, docIds[1], TEST_DOC_TEXT3, ft));
        w.flush();
        w.commit();

        SearchContext searchContext2 = mock(SearchContext.class);

        ContextIndexSearcher indexSearcher2 = mock(ContextIndexSearcher.class);
        IndexObjects indexObjects2 = createIndexObjects(1);
        IndexReader indexReader2 = indexObjects2.indexReader();
        when(indexSearcher2.getIndexReader()).thenReturn(indexReader2);
        when(searchContext2.searcher()).thenReturn(indexSearcher2);
        when(searchContext2.size()).thenReturn(1);

        when(searchContext2.queryCollectorManagers()).thenReturn(new HashMap<>());
        when(searchContext2.shouldUseConcurrentSearch()).thenReturn(true);

        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);

        Directory directory2 = newDirectory();
        final IndexWriter w2 = new IndexWriter(directory2, newIndexWriterConfig());
        FieldType ft2 = new FieldType(TextField.TYPE_NOT_STORED);
        ft2.setIndexOptions(random().nextBoolean() ? IndexOptions.DOCS : IndexOptions.DOCS_AND_FREQS);
        ft2.setOmitNorms(random().nextBoolean());
        ft2.freeze();

        w2.addDocument(getDocument(TEXT_FIELD_NAME, docIds[2], TEST_DOC_TEXT2, ft));
        w2.flush();
        w2.commit();

        IndexReader reader = DirectoryReader.open(w);
        IndexSearcher searcher = new IndexSearcher(reader);
        IndexReader reader2 = DirectoryReader.open(w2);
        IndexSearcher searcher2 = new IndexSearcher(reader2);

        CollectorManager hybridCollectorManager = HybridCollectorManager.createHybridCollectorManager(searchContext);
        SimpleFieldCollector collector1 = (SimpleFieldCollector) hybridCollectorManager.newCollector();
        SimpleFieldCollector collector2 = (SimpleFieldCollector) hybridCollectorManager.newCollector();

        Weight weight1 = new HybridQueryWeight(hybridQueryWithTerm, searcher, ScoreMode.TOP_SCORES, BoostingQueryBuilder.DEFAULT_BOOST);
        Weight weight2 = new HybridQueryWeight(hybridQueryWithTerm, searcher2, ScoreMode.TOP_SCORES, BoostingQueryBuilder.DEFAULT_BOOST);
        collector1.setWeight(weight1);
        collector2.setWeight(weight2);
        LeafReaderContext leafReaderContext = searcher.getIndexReader().leaves().get(0);
        LeafCollector leafCollector1 = collector1.getLeafCollector(leafReaderContext);

        LeafReaderContext leafReaderContext2 = searcher2.getIndexReader().leaves().get(0);
        LeafCollector leafCollector2 = collector2.getLeafCollector(leafReaderContext2);
        BulkScorer scorer = weight1.bulkScorer(leafReaderContext);
        scorer.score(leafCollector1, leafReaderContext.reader().getLiveDocs(), 0, DocIdSetIterator.NO_MORE_DOCS);
        leafCollector1.finish();
        BulkScorer scorer2 = weight2.bulkScorer(leafReaderContext2);
        scorer2.score(leafCollector2, leafReaderContext2.reader().getLiveDocs(), 0, DocIdSetIterator.NO_MORE_DOCS);
        leafCollector2.finish();

        Object results = hybridCollectorManager.reduce(List.of(collector1, collector2));

        assertNotNull(results);
        ReduceableSearchResult reduceableSearchResult = ((ReduceableSearchResult) results);
        QuerySearchResult querySearchResult = new QuerySearchResult();
        reduceableSearchResult.reduce(querySearchResult);
        TopDocsAndMaxScore topDocsAndMaxScore = querySearchResult.topDocs();

        assertNotNull(topDocsAndMaxScore);
        assertEquals(3, topDocsAndMaxScore.topDocs.totalHits.value());
        assertEquals(TotalHits.Relation.EQUAL_TO, topDocsAndMaxScore.topDocs.totalHits.relation());
        float maxScore = topDocsAndMaxScore.maxScore;
        assertTrue(maxScore > 0);
        FieldDoc[] fieldDocs = (FieldDoc[]) topDocsAndMaxScore.topDocs.scoreDocs;
        assertEquals(5, fieldDocs.length);
        assertEquals(1, fieldDocs[0].fields[0]);
        assertEquals(1, fieldDocs[1].fields[0]);
        assertEquals(fieldDocs[2].doc, fieldDocs[2].fields[0]);
        assertEquals(fieldDocs[3].doc, fieldDocs[3].fields[0]);
        assertEquals(1, fieldDocs[4].fields[0]);

        w.close();
        reader.close();
        directory.close();
        w2.close();
        reader2.close();
        directory2.close();
    }

    @SneakyThrows
    public void testReduceAndRescore_whenMatchedDocsAndRescoreContextPresent_thenSuccessful() {
        SearchContext searchContext = mock(SearchContext.class);
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) createMapperService().fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);
        // mock to query to generate the parsed query
        doAnswer(invocationOnMock -> {
            final QueryBuilder queryBuilder = invocationOnMock.getArgument(0);
            return new ParsedQuery(queryBuilder.toQuery(mockQueryShardContext), Collections.emptyMap());
        }).when(mockQueryShardContext).toQuery(any());
        HybridQueryContext hybridQueryContext = HybridQueryContext.builder().paginationDepth(10).build();

        HybridQuery hybridQueryWithTerm = new HybridQuery(
            List.of(
                QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY1).toQuery(mockQueryShardContext),
                QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY2).toQuery(mockQueryShardContext)
            ),
            hybridQueryContext
        );
        when(searchContext.query()).thenReturn(hybridQueryWithTerm);
        MapperService mapperService = createMapperService();
        when(searchContext.mapperService()).thenReturn(mapperService);
        ContextIndexSearcher indexSearcher = mock(ContextIndexSearcher.class);
        when(indexSearcher.getIndexReader()).thenReturn(indexReader);
        when(searchContext.searcher()).thenReturn(indexSearcher);
        when(searchContext.size()).thenReturn(2);

        Map<Class<?>, CollectorManager<? extends Collector, ReduceableSearchResult>> classCollectorManagerMap = new HashMap<>();
        when(searchContext.queryCollectorManagers()).thenReturn(classCollectorManagerMap);
        when(searchContext.shouldUseConcurrentSearch()).thenReturn(false);

        Directory directory = newDirectory();
        final IndexWriter w = new IndexWriter(directory, newIndexWriterConfig());
        FieldType ft = new FieldType(TextField.TYPE_NOT_STORED);
        ft.freeze();

        int docId1 = RandomizedTest.randomInt();
        int docId2 = RandomizedTest.randomInt();
        int docId3 = RandomizedTest.randomInt();
        w.addDocument(getDocument(TEXT_FIELD_NAME, docId1, TEST_DOC_TEXT1, ft));
        w.addDocument(getDocument(TEXT_FIELD_NAME, docId2, TEST_DOC_TEXT2, ft));
        w.addDocument(getDocument(TEXT_FIELD_NAME, docId3, TEST_DOC_TEXT3, ft));
        w.flush();
        w.commit();

        IndexReader reader = DirectoryReader.open(w);
        IndexSearcher searcher = new IndexSearcher(reader);

        RescorerBuilder<QueryRescorerBuilder> rescorerBuilder = new QueryRescorerBuilder(QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY2));
        RescoreContext rescoreContext = rescorerBuilder.buildContext(mockQueryShardContext);

        List<RescoreContext> rescoreContexts = List.of(rescoreContext);
        when(searchContext.rescore()).thenReturn(rescoreContexts);
        Weight rescoreWeight = mock(Weight.class);
        Scorer rescoreScorer = mock(Scorer.class);
        ScorerSupplier mockScorerSupplier = mock(ScorerSupplier.class);
        when(mockScorerSupplier.get(anyLong())).thenReturn(rescoreScorer);
        when(mockScorerSupplier.cost()).thenReturn(1L);
        when(rescoreWeight.scorerSupplier(any(LeafReaderContext.class))).thenReturn(mockScorerSupplier);

        when(rescoreScorer.docID()).thenReturn(1);
        DocIdSetIterator iterator = mock(DocIdSetIterator.class);
        when(rescoreScorer.iterator()).thenReturn(iterator);
        when(rescoreScorer.score()).thenReturn(0.9f);
        when(indexSearcher.createWeight(any(), eq(ScoreMode.COMPLETE), eq(1f))).thenReturn(rescoreWeight);

        CollectorManager hybridCollectorManager1 = HybridCollectorManager.createHybridCollectorManager(searchContext);
        HybridTopScoreDocCollector collector = (HybridTopScoreDocCollector) hybridCollectorManager1.newCollector();

        QueryBuilder postFilterQuery = QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY1);

        Query pfQuery = postFilterQuery.toQuery(mockQueryShardContext);
        ParsedQuery parsedQuery = new ParsedQuery(pfQuery);
        searchContext.parsedQuery(parsedQuery);
        when(searchContext.parsedPostFilter()).thenReturn(parsedQuery);
        when(indexSearcher.rewrite(pfQuery)).thenReturn(pfQuery);
        Weight postFilterWeight = mock(Weight.class);
        when(indexSearcher.createWeight(pfQuery, ScoreMode.COMPLETE_NO_SCORES, 1f)).thenReturn(postFilterWeight);

        CollectorManager hybridCollectorManager2 = HybridCollectorManager.createHybridCollectorManager(searchContext);
        FilteredCollector filteredCollector = (FilteredCollector) hybridCollectorManager2.newCollector();

        Weight weight = new HybridQueryWeight(hybridQueryWithTerm, searcher, ScoreMode.TOP_SCORES, BoostingQueryBuilder.DEFAULT_BOOST);
        collector.setWeight(weight);
        filteredCollector.setWeight(weight);
        LeafReaderContext leafReaderContext = searcher.getIndexReader().leaves().get(0);
        LeafCollector leafCollector = collector.getLeafCollector(leafReaderContext);
        LeafCollector filteredCollectorLeafCollector = filteredCollector.getLeafCollector(leafReaderContext);
        BulkScorer scorer = weight.bulkScorer(leafReaderContext);
        scorer.score(leafCollector, leafReaderContext.reader().getLiveDocs(), 0, DocIdSetIterator.NO_MORE_DOCS);
        leafCollector.finish();
        scorer.score(filteredCollectorLeafCollector, leafReaderContext.reader().getLiveDocs(), 0, DocIdSetIterator.NO_MORE_DOCS);
        filteredCollectorLeafCollector.finish();

        Object results1 = hybridCollectorManager1.reduce(List.of());
        Object results2 = hybridCollectorManager2.reduce(List.of());

        assertNotNull(results1);
        assertNotNull(results2);
        ReduceableSearchResult reduceableSearchResult = ((ReduceableSearchResult) results1);
        QuerySearchResult querySearchResult = new QuerySearchResult();
        reduceableSearchResult.reduce(querySearchResult);
        TopDocsAndMaxScore topDocsAndMaxScore = querySearchResult.topDocs();

        assertNotNull(topDocsAndMaxScore);
        assertEquals(2, topDocsAndMaxScore.topDocs.totalHits.value());
        assertEquals(TotalHits.Relation.EQUAL_TO, topDocsAndMaxScore.topDocs.totalHits.relation());
        float maxScore = topDocsAndMaxScore.maxScore;
        assertTrue(maxScore > 0);
        ScoreDoc[] scoreDocs = topDocsAndMaxScore.topDocs.scoreDocs;
        assertEquals(6, scoreDocs.length);
        assertEquals(MAGIC_NUMBER_START_STOP, scoreDocs[0].score, DELTA_FOR_ASSERTION);
        assertEquals(MAGIC_NUMBER_DELIMITER, scoreDocs[1].score, DELTA_FOR_ASSERTION);
        assertEquals(MAGIC_NUMBER_DELIMITER, scoreDocs[3].score, DELTA_FOR_ASSERTION);
        assertEquals(MAGIC_NUMBER_START_STOP, scoreDocs[5].score, DELTA_FOR_ASSERTION);

        if (Math.abs(scoreDocs[2].score - maxScore) <= DELTA_FOR_ASSERTION) {
            assertEquals(maxScore, scoreDocs[2].score, DELTA_FOR_ASSERTION);
            assertTrue(scoreDocs[4].score <= maxScore);
        } else if (Math.abs(scoreDocs[4].score - maxScore) <= DELTA_FOR_ASSERTION) {
            assertTrue(scoreDocs[2].score <= maxScore);
            assertEquals(maxScore, scoreDocs[4].score, DELTA_FOR_ASSERTION);
        } else {
            fail("neither scoreDocs[2] nor scoreDocs[4] equals maxScore");
        }

        w.close();
        reader.close();
        directory.close();
    }

    @SneakyThrows
    public void testRescoreWithConcurrentSegmentSearch_whenMatchedDocsAndRescore_thenSuccessful() {
        SearchContext searchContext = mock(SearchContext.class);
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) createMapperService().fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);
        // mock to query to generate the parsed query
        doAnswer(invocationOnMock -> {
            final QueryBuilder queryBuilder = invocationOnMock.getArgument(0);
            return new ParsedQuery(queryBuilder.toQuery(mockQueryShardContext), Collections.emptyMap());
        }).when(mockQueryShardContext).toQuery(any());
        HybridQueryContext hybridQueryContext = HybridQueryContext.builder().paginationDepth(10).build();

        HybridQuery hybridQueryWithTerm = new HybridQuery(
            List.of(
                QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY1).toQuery(mockQueryShardContext),
                QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY2).toQuery(mockQueryShardContext),
                QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY3).toQuery(mockQueryShardContext)
            ),
            hybridQueryContext
        );
        when(searchContext.query()).thenReturn(hybridQueryWithTerm);
        MapperService mapperService = createMapperService();
        when(searchContext.mapperService()).thenReturn(mapperService);
        ContextIndexSearcher indexSearcher = mock(ContextIndexSearcher.class);
        IndexObjects indexObjects = createIndexObjects(2);
        IndexReader indexReader = indexObjects.indexReader();
        when(indexSearcher.getIndexReader()).thenReturn(indexReader);
        when(searchContext.searcher()).thenReturn(indexSearcher);
        when(searchContext.size()).thenReturn(1);

        Map<Class<?>, CollectorManager<? extends Collector, ReduceableSearchResult>> classCollectorManagerMap = new HashMap<>();
        when(searchContext.queryCollectorManagers()).thenReturn(classCollectorManagerMap);
        when(searchContext.shouldUseConcurrentSearch()).thenReturn(true);
        // index segment 1
        Directory directory = newDirectory();
        final IndexWriter w = new IndexWriter(directory, newIndexWriterConfig());
        FieldType ft = new FieldType(TextField.TYPE_NOT_STORED);
        ft.freeze();

        int docId1 = RandomizedTest.randomInt();
        int docId2 = RandomizedTest.randomInt();
        int docId3 = RandomizedTest.randomInt();

        w.addDocument(getDocument(TEXT_FIELD_NAME, docId1, TEST_DOC_TEXT1, ft));
        w.addDocument(getDocument(TEXT_FIELD_NAME, docId2, TEST_DOC_TEXT2, ft));
        w.flush();
        w.commit();

        // index segment 2
        SearchContext searchContext2 = mock(SearchContext.class);

        ContextIndexSearcher indexSearcher2 = mock(ContextIndexSearcher.class);
        IndexObjects indexObjects2 = createIndexObjects(1);
        IndexReader indexReader2 = indexObjects2.indexReader();
        when(indexSearcher2.getIndexReader()).thenReturn(indexReader2);
        when(searchContext2.searcher()).thenReturn(indexSearcher2);
        when(searchContext2.size()).thenReturn(1);

        when(searchContext2.queryCollectorManagers()).thenReturn(new HashMap<>());
        when(searchContext2.shouldUseConcurrentSearch()).thenReturn(true);

        Directory directory2 = newDirectory();
        final IndexWriter w2 = new IndexWriter(directory2, newIndexWriterConfig());
        FieldType ft2 = new FieldType(TextField.TYPE_NOT_STORED);
        ft2.freeze();

        w2.addDocument(getDocument(TEXT_FIELD_NAME, docId3, TEST_DOC_TEXT3, ft));
        w2.flush();
        w2.commit();

        IndexReader reader1 = DirectoryReader.open(w);
        IndexSearcher searcher1 = new IndexSearcher(reader1);
        IndexReader reader2 = DirectoryReader.open(w2);
        IndexSearcher searcher2 = new IndexSearcher(reader2);

        RescorerBuilder<QueryRescorerBuilder> rescorerBuilder = new QueryRescorerBuilder(QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY2));
        RescoreContext rescoreContext = rescorerBuilder.buildContext(mockQueryShardContext);
        List<RescoreContext> rescoreContexts = List.of(rescoreContext);
        when(searchContext.rescore()).thenReturn(rescoreContexts);
        Weight rescoreWeight = mock(Weight.class);
        Scorer rescoreScorer = mock(Scorer.class);
        ScorerSupplier mockScorerSupplier = mock(ScorerSupplier.class);
        when(mockScorerSupplier.get(anyLong())).thenReturn(rescoreScorer);
        when(mockScorerSupplier.cost()).thenReturn(1L);
        when(rescoreWeight.scorerSupplier(any(LeafReaderContext.class))).thenReturn(mockScorerSupplier);

        when(rescoreScorer.docID()).thenReturn(1);
        DocIdSetIterator iterator = mock(DocIdSetIterator.class);
        when(rescoreScorer.iterator()).thenReturn(iterator);
        when(rescoreScorer.score()).thenReturn(0.9f);
        when(indexSearcher.createWeight(any(), eq(ScoreMode.COMPLETE), eq(1f))).thenReturn(rescoreWeight);

        CollectorManager hybridCollectorManager = HybridCollectorManager.createHybridCollectorManager(searchContext);
        HybridTopScoreDocCollector collector1 = (HybridTopScoreDocCollector) hybridCollectorManager.newCollector();
        HybridTopScoreDocCollector collector2 = (HybridTopScoreDocCollector) hybridCollectorManager.newCollector();

        Weight weight1 = new HybridQueryWeight(hybridQueryWithTerm, searcher1, ScoreMode.TOP_SCORES, BoostingQueryBuilder.DEFAULT_BOOST);
        Weight weight2 = new HybridQueryWeight(hybridQueryWithTerm, searcher2, ScoreMode.TOP_SCORES, BoostingQueryBuilder.DEFAULT_BOOST);
        collector1.setWeight(weight1);
        collector2.setWeight(weight2);

        LeafReaderContext leafReaderContext = searcher1.getIndexReader().leaves().get(0);
        LeafCollector leafCollector1 = collector1.getLeafCollector(leafReaderContext);
        BulkScorer scorer = weight1.bulkScorer(leafReaderContext);
        scorer.score(leafCollector1, leafReaderContext.reader().getLiveDocs(), 0, DocIdSetIterator.NO_MORE_DOCS);
        leafCollector1.finish();

        LeafReaderContext leafReaderContext2 = searcher2.getIndexReader().leaves().get(0);
        LeafCollector leafCollector2 = collector2.getLeafCollector(leafReaderContext2);
        BulkScorer scorer2 = weight2.bulkScorer(leafReaderContext2);
        scorer2.score(leafCollector2, leafReaderContext2.reader().getLiveDocs(), 0, DocIdSetIterator.NO_MORE_DOCS);
        leafCollector2.finish();

        Object results = hybridCollectorManager.reduce(List.of(collector1, collector2));

        // assert that second search hit in result has the max score due to boots from rescorer
        assertNotNull(results);
        ReduceableSearchResult reduceableSearchResult = ((ReduceableSearchResult) results);
        QuerySearchResult querySearchResult = new QuerySearchResult();
        reduceableSearchResult.reduce(querySearchResult);
        TopDocsAndMaxScore topDocsAndMaxScore = querySearchResult.topDocs();

        assertNotNull(topDocsAndMaxScore);
        assertEquals(3, topDocsAndMaxScore.topDocs.totalHits.value());
        assertEquals(TotalHits.Relation.EQUAL_TO, topDocsAndMaxScore.topDocs.totalHits.relation());
        float maxScore = topDocsAndMaxScore.maxScore;
        assertTrue(maxScore > 0);
        ScoreDoc[] scoreDocs = topDocsAndMaxScore.topDocs.scoreDocs;
        assertEquals(8, scoreDocs.length);
        assertEquals(MAGIC_NUMBER_START_STOP, scoreDocs[0].score, DELTA_FOR_ASSERTION);
        assertEquals(MAGIC_NUMBER_DELIMITER, scoreDocs[1].score, DELTA_FOR_ASSERTION);
        assertTrue(maxScore > scoreDocs[2].score);
        assertEquals(MAGIC_NUMBER_DELIMITER, scoreDocs[3].score, DELTA_FOR_ASSERTION);
        assertEquals(maxScore, scoreDocs[4].score, DELTA_FOR_ASSERTION);
        assertEquals(MAGIC_NUMBER_DELIMITER, scoreDocs[5].score, DELTA_FOR_ASSERTION);
        assertTrue(maxScore > scoreDocs[6].score);
        assertEquals(MAGIC_NUMBER_START_STOP, scoreDocs[7].score, DELTA_FOR_ASSERTION);

        // release resources
        w.close();
        reader1.close();
        directory.close();
        w2.close();
        reader2.close();
        directory2.close();
    }

    @SneakyThrows
    public void testReduceAndRescore_whenRescorerThrowsException_thenFail() {
        SearchContext searchContext = mock(SearchContext.class);
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) createMapperService().fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);
        HybridQueryContext hybridQueryContext = HybridQueryContext.builder().paginationDepth(10).build();

        HybridQuery hybridQueryWithTerm = new HybridQuery(
            List.of(
                QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY1).toQuery(mockQueryShardContext),
                QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY2).toQuery(mockQueryShardContext)
            ),
            hybridQueryContext
        );
        when(searchContext.query()).thenReturn(hybridQueryWithTerm);
        MapperService mapperService = createMapperService();
        when(searchContext.mapperService()).thenReturn(mapperService);
        ContextIndexSearcher indexSearcher = mock(ContextIndexSearcher.class);
        when(indexSearcher.getIndexReader()).thenReturn(indexReader);
        when(searchContext.searcher()).thenReturn(indexSearcher);
        when(searchContext.size()).thenReturn(2);

        Map<Class<?>, CollectorManager<? extends Collector, ReduceableSearchResult>> classCollectorManagerMap = new HashMap<>();
        when(searchContext.queryCollectorManagers()).thenReturn(classCollectorManagerMap);
        when(searchContext.shouldUseConcurrentSearch()).thenReturn(false);

        Directory directory = newDirectory();
        final IndexWriter w = new IndexWriter(directory, newIndexWriterConfig());
        FieldType ft = new FieldType(TextField.TYPE_NOT_STORED);
        ft.freeze();

        int docId1 = RandomizedTest.randomInt();
        w.addDocument(getDocument(TEXT_FIELD_NAME, docId1, TEST_DOC_TEXT1, ft));
        w.flush();
        w.commit();

        IndexReader reader = DirectoryReader.open(w);
        IndexSearcher searcher = new IndexSearcher(reader);

        RescoreContext rescoreContext = mock(RescoreContext.class);
        Rescorer rescorer = mock(Rescorer.class);
        when(rescoreContext.rescorer()).thenReturn(rescorer);
        when(rescorer.rescore(any(), any(), any())).thenThrow(new IOException("something happened with rescorer"));
        List<RescoreContext> rescoreContexts = List.of(rescoreContext);
        when(searchContext.rescore()).thenReturn(rescoreContexts);

        CollectorManager hybridCollectorManager1 = HybridCollectorManager.createHybridCollectorManager(searchContext);
        HybridTopScoreDocCollector collector = (HybridTopScoreDocCollector) hybridCollectorManager1.newCollector();

        Weight weight = new HybridQueryWeight(hybridQueryWithTerm, searcher, ScoreMode.TOP_SCORES, BoostingQueryBuilder.DEFAULT_BOOST);
        collector.setWeight(weight);

        LeafReaderContext leafReaderContext = searcher.getIndexReader().leaves().get(0);
        LeafCollector leafCollector = collector.getLeafCollector(leafReaderContext);

        BulkScorer scorer = weight.bulkScorer(leafReaderContext);
        scorer.score(leafCollector, leafReaderContext.reader().getLiveDocs(), 0, DocIdSetIterator.NO_MORE_DOCS);
        leafCollector.finish();

        expectThrows(HybridSearchRescoreQueryException.class, () -> hybridCollectorManager1.reduce(List.of()));

        // release resources
        w.close();
        reader.close();
        directory.close();
    }

    @SneakyThrows
    public void testCreateCollectorManager_whenFromAreEqualToZeroAndPaginationDepthInRange_thenSuccessful() {
        SearchContext searchContext = mock(SearchContext.class);
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) createMapperService().fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);
        TermQueryBuilder termSubQuery = QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY1);
        HybridQueryContext hybridQueryContext = HybridQueryContext.builder().paginationDepth(10).build();

        // pagination_depth=10
        HybridQuery hybridQuery = new HybridQuery(List.of(termSubQuery.toQuery(mockQueryShardContext)), hybridQueryContext);

        when(searchContext.query()).thenReturn(hybridQuery);
        MapperService mapperService = createMapperService();
        when(searchContext.mapperService()).thenReturn(mapperService);
        ContextIndexSearcher indexSearcher = mock(ContextIndexSearcher.class);
        when(indexSearcher.getIndexReader()).thenReturn(indexReader);
        when(searchContext.searcher()).thenReturn(indexSearcher);

        Map<Class<?>, CollectorManager<? extends Collector, ReduceableSearchResult>> classCollectorManagerMap = new HashMap<>();
        when(searchContext.queryCollectorManagers()).thenReturn(classCollectorManagerMap);
        when(searchContext.shouldUseConcurrentSearch()).thenReturn(false);

        CollectorManager hybridCollectorManager = HybridCollectorManager.createHybridCollectorManager(searchContext);
        assertNotNull(hybridCollectorManager);
        assertTrue(hybridCollectorManager instanceof HybridCollectorManager.HybridCollectorNonConcurrentManager);

        Collector collector = hybridCollectorManager.newCollector();
        assertNotNull(collector);
        assertTrue(collector instanceof HybridTopScoreDocCollector);

        Collector secondCollector = hybridCollectorManager.newCollector();
        assertSame(collector, secondCollector);
    }

    @SneakyThrows
    public void testScrollWithHybridQuery_thenFail() {
        SearchContext searchContext = mock(SearchContext.class);
        ScrollContext scrollContext = new ScrollContext();
        when(searchContext.scrollContext()).thenReturn(scrollContext);
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) createMapperService().fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);
        TermQueryBuilder termSubQuery = QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY1);
        HybridQueryContext hybridQueryContext = HybridQueryContext.builder().paginationDepth(10).build();

        HybridQuery hybridQuery = new HybridQuery(List.of(termSubQuery.toQuery(mockQueryShardContext)), hybridQueryContext);

        when(searchContext.query()).thenReturn(hybridQuery);
        ContextIndexSearcher indexSearcher = mock(ContextIndexSearcher.class);
        when(indexSearcher.getIndexReader()).thenReturn(indexReader);
        when(searchContext.searcher()).thenReturn(indexSearcher);

        Map<Class<?>, CollectorManager<? extends Collector, ReduceableSearchResult>> classCollectorManagerMap = new HashMap<>();
        when(searchContext.queryCollectorManagers()).thenReturn(classCollectorManagerMap);
        when(searchContext.shouldUseConcurrentSearch()).thenReturn(false);

        IllegalArgumentException illegalArgumentException = assertThrows(
            IllegalArgumentException.class,
            () -> HybridCollectorManager.createHybridCollectorManager(searchContext)
        );
        assertEquals(
            String.format(Locale.ROOT, "Scroll operation is not supported in hybrid query"),
            illegalArgumentException.getMessage()
        );
    }

    @SneakyThrows
    public void testCreateCollectorManager_whenPaginationDepthIsEqualToNullAndFromIsGreaterThanZero_thenFail() {
        SearchContext searchContext = mock(SearchContext.class);
        // From >0
        when(searchContext.from()).thenReturn(5);
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) createMapperService().fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);
        TermQueryBuilder termSubQuery = QueryBuilders.termQuery(TEXT_FIELD_NAME, QUERY1);

        HybridQuery hybridQuery = new HybridQuery(
            List.of(termSubQuery.toQuery(mockQueryShardContext)),
            HybridQueryContext.builder().build() // pagination_depth is set to null
        );

        when(searchContext.query()).thenReturn(hybridQuery);
        ContextIndexSearcher indexSearcher = mock(ContextIndexSearcher.class);
        when(indexSearcher.getIndexReader()).thenReturn(indexReader);
        when(searchContext.searcher()).thenReturn(indexSearcher);
        MapperService mapperService = createMapperService();
        when(searchContext.mapperService()).thenReturn(mapperService);

        Map<Class<?>, CollectorManager<? extends Collector, ReduceableSearchResult>> classCollectorManagerMap = new HashMap<>();
        when(searchContext.queryCollectorManagers()).thenReturn(classCollectorManagerMap);
        when(searchContext.shouldUseConcurrentSearch()).thenReturn(false);

        IllegalArgumentException illegalArgumentException = assertThrows(
            IllegalArgumentException.class,
            () -> HybridCollectorManager.createHybridCollectorManager(searchContext)
        );
        assertEquals(
            String.format(Locale.ROOT, "pagination_depth param is missing in the search request"),
            illegalArgumentException.getMessage()
        );
    }

    private IndexObjects createIndexObjects(int numDocs) throws IOException {
        Directory directory = new ByteBuffersDirectory();
        IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig());

        // Add the specified number of documents
        for (int i = 1; i <= numDocs; i++) {
            Document doc = new Document();
            doc.add(new StringField("field", "value" + i, Field.Store.YES));
            writer.addDocument(doc);
        }

        writer.commit();
        IndexReader indexReader = DirectoryReader.open(writer);

        return new IndexObjects(indexReader, directory, writer);
    }

    private record IndexObjects(IndexReader indexReader, Directory directory, IndexWriter writer) {
    }
}
