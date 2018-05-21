package edu.cmu.cs.lti.script.annotators;

import com.google.common.collect.Table;
import edu.cmu.cs.lti.script.type.*;
import edu.cmu.cs.lti.uima.annotator.AbstractLoggingAnnotator;
import edu.cmu.cs.lti.uima.util.UimaAnnotationUtils;
import edu.cmu.cs.lti.uima.util.UimaNlpUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.fit.util.FSCollectionFactory;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;

import java.util.*;

/**
 * Created with IntelliJ IDEA.
 * Date: 4/1/18
 * Time: 5:14 PM
 *
 * @author Zhengzhong Liu
 */
public class VerbBasedEventDetector extends AbstractLoggingAnnotator {

    private Set<String> ignoredHeadWords;

    @Override
    public void initialize(UimaContext aContext) throws ResourceInitializationException {
        super.initialize(aContext);
        String[] ignoredVerbs = new String[]{"become", "be", "do", "have", "seem", "go", "have", "keep", "argue",
                "claim",
                "say", "suggest", "tell"};

        ignoredHeadWords = new HashSet<>();
        Collections.addAll(ignoredHeadWords, ignoredVerbs);
    }

    @Override
    public void process(JCas aJCas) throws AnalysisEngineProcessException {
        if (!aJCas.getDocumentLanguage().equals("en")) {
            return;
        }

        Map<Word, EntityMention> h2Entities = UimaNlpUtils.indexEntityMentions(aJCas);
        Table<Integer, Integer, EventMention> span2Events = UimaNlpUtils.indexEventMentions(aJCas);

        int eventId = 0;
        for (StanfordCorenlpToken token : JCasUtil.select(aJCas, StanfordCorenlpToken.class)) {
            if (!token.getPos().startsWith("V")) {
                continue;
            } else if (ignoredHeadWords.contains(token.getLemma().toLowerCase())) {
                continue;
            }

            EventMention eventMention;
            if (span2Events.contains(token.getBegin(), token.getEnd())) {
                eventMention = span2Events.get(token.getBegin(), token.getEnd());
            } else {
                eventMention = new EventMention(aJCas, token.getBegin(), token.getEnd());
                UimaAnnotationUtils.finishAnnotation(eventMention, COMPONENT_ID, eventId++, aJCas);
            }
            eventMention.setHeadWord(token);
            eventMention.setEventType("Verbal");

            createDependencyArgs(aJCas, eventMention, h2Entities, COMPONENT_ID);
        }

        UimaNlpUtils.fixEntityMentions(aJCas, new ArrayList<>(JCasUtil.select(aJCas, EntityMention.class)),
                COMPONENT_ID);
    }

    public static void createDependencyArgs(JCas aJCas, EventMention eventMention, Map<Word, EntityMention>
            h2Entities, String COMPONENT_ID) {
        Word headToken = eventMention.getHeadWord();
        Map<String, Word> args = getArgs(headToken);

        Map<Word, EventMentionArgumentLink> head2Args = UimaNlpUtils.indexArgs(eventMention);
        List<EventMentionArgumentLink> argumentLinks = new ArrayList<>(head2Args.values());

        for (Map.Entry<String, Word> arg : args.entrySet()) {
            String role = arg.getKey();
            Word argWord = arg.getValue();

            EventMentionArgumentLink argumentLink;
            if (head2Args.containsKey(argWord)) {
                argumentLink = head2Args.get(argWord);
            } else {
                argumentLink = UimaNlpUtils.createArg(aJCas, h2Entities, eventMention, argWord.getBegin(),
                        argWord.getEnd(), COMPONENT_ID);
                argumentLinks.add(argumentLink);
            }
            argumentLink.setArgumentRole(role);
        }
        eventMention.setArguments(FSCollectionFactory.createFSList(aJCas, argumentLinks));
    }

    public static Map<String, Word> getArgs(Word predicate) {
        Map<String, Word> args = new HashMap<>();
        if (predicate.getChildDependencyRelations() != null) {
            for (StanfordDependencyRelation dep : FSCollectionFactory.create(predicate
                    .getChildDependencyRelations(), StanfordDependencyRelation.class)) {
                Pair<String, Word> child = takeDep(dep);

                if (child == null) {
                    continue;
                }
                Word word = child.getRight();
                String role = child.getLeft();
                args.put(role, word);
            }
        }

        return args;
    }

    public static Pair<String, Word> takeDep(StanfordDependencyRelation dep) {
        String depType = dep.getDependencyType();
        Word depWord = dep.getChild();
        if (depType.equals("nsubj") || depType.contains("agent")) {
            return Pair.of("subj", depWord);
        } else if (depType.equals("dobj") || depType.equals("nsubjpass")) {
            return Pair.of("obj", depWord);
        } else if (depType.equals("iobj")) {
            return Pair.of("iobj", depWord);
        } else if (depType.startsWith("prep_")) {
            return Pair.of(depType, depWord);
        }

        return null;
    }
}
