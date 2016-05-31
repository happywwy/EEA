package edu.cmu.cs.lti.learning.update;

import edu.cmu.cs.lti.learning.model.*;
import edu.cmu.cs.lti.learning.model.decoding.JointLabelLinkAgenda;
import edu.cmu.cs.lti.learning.model.decoding.NodeLinkingState;
import edu.cmu.cs.lti.learning.model.graph.EdgeType;
import edu.cmu.cs.lti.utils.DebugUtils;
import gnu.trove.map.TObjectDoubleMap;
import gnu.trove.map.hash.TObjectDoubleHashMap;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static edu.cmu.cs.lti.learning.model.ModelConstants.COREF_MODEL_NAME;
import static edu.cmu.cs.lti.learning.model.ModelConstants.TYPE_MODEL_NAME;

/**
 * Created with IntelliJ IDEA.
 * Date: 2/28/16
 * Time: 4:31 PM
 *
 * @author Zhengzhong Liu
 */
public class DiscriminativeUpdater {
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    private Map<String, GraphWeightVector> allWeights;

    private Map<String, GraphFeatureVector> allDelta;

    private TObjectDoubleMap<String> allLoss;

    private Map<String, ClassAlphabet> classAlphabets;

    private Map<String, FeatureAlphabet> featureAlphabets;

//    private Map<String, TrainingStats> trainingStats;

    private double defaultStepSize = 0.1; // Default step size used by the perceptron trainer.

    private SeqLoss labelLosser;

    private boolean usePa;

    private String[] modelNames = new String[]{TYPE_MODEL_NAME, COREF_MODEL_NAME};

    private boolean updateMention;
    private boolean updateCoref;

    private double aggressiveness;
    private boolean usePAI;

    // TODO temporary debug clause;
    public static boolean debugger = true;

    private boolean useCorefUpdateConstraint = false;

    private int corefUpdateStrategy;

    private int numCorefUpdates = 0;

    public DiscriminativeUpdater(boolean updateMention, boolean updateCoref, boolean usePaUpdate, String lossType,
                                 double aggressiveness) {
        allWeights = new HashMap<>();
        allDelta = new HashMap<>();
        allLoss = new TObjectDoubleHashMap<>();
        classAlphabets = new HashMap<>();
        featureAlphabets = new HashMap<>();
        labelLosser = SeqLoss.getLoss(lossType);
//        trainingStats = new HashMap<>();

        if (!(updateMention || updateCoref)) {
            throw new IllegalArgumentException("Cannot use a updater without updating anything.");
        }

        usePa = usePaUpdate;

        this.updateMention = updateMention;
        this.updateCoref = updateCoref;

        this.aggressiveness = aggressiveness;
        usePAI = true;
    }

    public DiscriminativeUpdater(boolean updateMention, boolean updateCoref, boolean usePaUpdate, String lossType) {
        allWeights = new HashMap<>();
        allDelta = new HashMap<>();
        allLoss = new TObjectDoubleHashMap<>();
        classAlphabets = new HashMap<>();
        featureAlphabets = new HashMap<>();
        labelLosser = SeqLoss.getLoss(lossType);
//        trainingStats = new HashMap<>();

        if (!(updateMention || updateCoref)) {
            throw new IllegalArgumentException("Cannot use a updater without updating anything.");
        }

        usePa = usePaUpdate;

        this.updateMention = updateMention;
        this.updateCoref = updateCoref;
    }

    public void useCorefUpdateConstraint(int strategy) {
        useCorefUpdateConstraint = true;
        corefUpdateStrategy = strategy;

        if (strategy != 0 && strategy != 1 && strategy != 2) {
            throw new IllegalArgumentException("Unknown coreference constraint strategy!");
        }

        logger.info("Updater started with coreference constraint of strategy " + strategy);
        DebugUtils.pause(logger);
    }

    public void addWeightVector(String name, GraphWeightVector weightVector) {
        allWeights.put(name, weightVector);
        classAlphabets.put(name, weightVector.getClassAlphabet());
        featureAlphabets.put(name, weightVector.getFeatureAlphabet());
        allDelta.put(name, newDelta(name));
        allLoss.put(name, 0);

        logger.info("Adding weight vector " + name);
    }

    public GraphWeightVector getWeightVector(String weightKey) {
        return allWeights.get(weightKey);
    }

    public void recordLaSOUpdate(JointLabelLinkAgenda decodingAgenda, JointLabelLinkAgenda goldAgenda) {
        // TODO temporary debug clause.
        if (debugger) {
            MultiNodeKey currentGold = goldAgenda.getBestBeamState().getLastNodeResult();

            if (!currentGold.getCombinedType().equals(ClassAlphabet.noneOfTheAboveClass)) {
//                logger.debug("Recording laso update.");
//                logger.debug("Decoding states.");
//                for (NodeLinkingState s : decodingAgenda.getOrderedStates()) {
//                    logger.debug(s.toString());
//                }
//
//                logger.debug("Gold states.");
//                for (NodeLinkingState nodeLinkingState : goldAgenda.getOrderedStates()) {
//                    logger.debug(nodeLinkingState.toString());
//                }
            }
        }

        boolean nothingToRecord = false;
        if (!decodingAgenda.contains(goldAgenda)) {
//            logger.debug("Recording differences for LaSO update.");
            recordUpdate(decodingAgenda, goldAgenda);

            // Copy the gold agenda to decoding agenda (LaSO).
            decodingAgenda.copyFrom(goldAgenda);

            // Clear these features from both agenda, since they are assumed to be the same from here.
            goldAgenda.clearFeatures();
            decodingAgenda.clearFeatures();
        } else {
            nothingToRecord = true;
        }

        // TODO temporary debug clause.
        if (debugger) {
            if (nothingToRecord) {
//                logger.debug("Nothing to record.");
            }
//            DebugUtils.pause(logger);
        }
    }

    public void recordFinalUpdate(JointLabelLinkAgenda decodingAgenda, JointLabelLinkAgenda goldAgenda) {
        logger.debug("Recording final update difference between the top states.");
        logger.debug("Final decoding is ");
        logger.debug(decodingAgenda.getBestBeamState().toString());

        logger.debug("Final gold is ");
        logger.debug(goldAgenda.getBeamStates().toString());

        if (!decodingAgenda.getBestBeamState().match(goldAgenda.getBestBeamState())) {
            recordUpdate(decodingAgenda, goldAgenda);
        }
    }

    private void recordUpdate(JointLabelLinkAgenda decodingAgenda, JointLabelLinkAgenda goldAgenda) {
        NodeLinkingState bestDecoding = decodingAgenda.getBestBeamState();
        NodeLinkingState bestGold = goldAgenda.getBestBeamState();

        Pair<Double, Double> losses = bestDecoding.loss(bestGold, labelLosser);

        Double typeLoss = losses.getLeft();
        Double corefLoss = losses.getRight();

        addLoss(TYPE_MODEL_NAME, typeLoss);

        if (updateMention) {
            // Compute delta on labeling.
            GraphFeatureVector goldLabelFeature = goldAgenda.getBestDeltaLabelFv();
            GraphFeatureVector decodingLabelFeature = decodingAgenda.getBestDeltaLabelFv();

//            logger.debug("Showing mention features from  gold");
//            logger.debug(goldLabelFeature.readableNodeVector());
//            logger.debug("Showing mention features from  decoding");
//            logger.debug(decodingLabelFeature.readableNodeVector());

            GraphFeatureVector deltaMentionVector = allDelta.get(TYPE_MODEL_NAME);
            deltaMentionVector.extend(goldLabelFeature);
            deltaMentionVector.extend(decodingLabelFeature, -1);

//            logger.debug("Current mention delta:");
//            logger.debug(deltaMentionVector.readableNodeVector());
//            logger.debug("New mention loss is " + losses.getLeft() + " total is now " + allLoss.get(TYPE_MODEL_NAME));
//            logger.debug("Mention loss is " + losses.getLeft());
        }

        // Compute delta on coreferecence.
        if (updateCoref) {
            if (useCorefUpdateConstraint) {
                if (!allowCorefUpdate(bestDecoding, bestGold)) {
                    if (debugger) {
//                        logger.debug("Do not update because mention types criteria is not met.");
//                        logger.debug(bestDecoding.showNodes());
//                        logger.debug(bestGold.showNodes());
                    }
                    return;
                }

                if (typeLoss != 0) {
                    return;
                }
            }

            addLoss(COREF_MODEL_NAME, corefLoss);

            numCorefUpdates++;

            GraphFeatureVector deltaCorefVector = allDelta.get(COREF_MODEL_NAME);
//            logger.debug("Computing delta on gold and decoding.");

            for (Map.Entry<EdgeType, FeatureVector> goldFv : goldAgenda.getBestDeltaCorefVectors().entrySet()) {
                deltaCorefVector.extend(goldFv.getValue(), goldFv.getKey().name());
//                logger.debug("Gold feature edge type : " + goldFv.getKey());
//                logger.debug(goldFv.getValue().readableString());
            }
            for (Map.Entry<EdgeType, FeatureVector> decoding : decodingAgenda.getBestDeltaCorefVectors().entrySet()) {
                deltaCorefVector.extend(decoding.getValue().negation(), decoding.getKey().name());
//                logger.debug("System feature edge type : " + decoding.getKey());
//                logger.debug(decoding.getValue().readableString());
            }

            if (logger.isDebugEnabled() && debugger) {
//                logger.debug("Best decoding.");
//                logger.debug(bestDecoding.toString());
//
//                logger.debug("Best gold.");
//                logger.debug(bestGold.toString());
//
//                for (Map.Entry<EdgeType, FeatureVector> goldFv : goldAgenda.getBestDeltaCorefVectors().entrySet()) {
//                    logger.debug("Gold feature edge type : " + goldFv.getKey());
//                    logger.debug(goldFv.getValue().readableString());
//                }
//
//                for (Map.Entry<EdgeType, FeatureVector> decoding : decodingAgenda.getBestDeltaCorefVectors().entrySet
//                        ()) {
//                    logger.debug("System feature edge type : " + decoding.getKey());
//                    logger.debug(decoding.getValue().readableString());
//                }
//
//                logger.debug("Coreference delta is.");
//                logger.debug(deltaCorefVector.readableNodeVector());
//                logger.debug("New coref loss is " + losses.getRight() + " total is now " +
//                        allLoss.get(COREF_MODEL_NAME));
//                if (losses.getRight() != 0) {
//                    DebugUtils.pause(logger);
//                }
            }

            if (!updateMention) {
                if (deltaCorefVector.getFeatureL2() == 0) {
                    double loss = decodingAgenda.getBestBeamState().getDecodingTree().getLoss(goldAgenda
                            .getBestBeamState().getDecodingTree());

                    logger.warn("Loss is  " + loss + " but L2 is 0 for coref vector, features are not good enough.");

                    logger.warn("Best Decoding is : ");
                    logger.warn(decodingAgenda.getBestBeamState().toString());

                    logger.warn("Gold is : ");
                    logger.warn(goldAgenda.getBestBeamState().toString());

                    logger.warn("Delta coref vector is :");
                    logger.warn(deltaCorefVector.readableNodeVector());
                }
            }
        }
    }

    private boolean allowCorefUpdate(NodeLinkingState bestDecodingState, NodeLinkingState bestGoldState) {
        List<MultiNodeKey> bestDecoding = bestDecodingState.getNodeResults();
        List<MultiNodeKey> bestGold = bestGoldState.getNodeResults();
        switch (corefUpdateStrategy) {
            case 0:
                return updateWithFullHistoryMentionCorrect(bestDecoding, bestGold);
            case 1:
                return updateWithFullHistoryTypeCorrect(bestDecoding, bestGold);
            case 2:
                return updateWithFullHistoryNonInvention(bestDecoding, bestGold);
            default:
                throw new IllegalArgumentException("Unknown coreference update strategy: " + corefUpdateStrategy);
        }
    }

    private boolean updateWithFullHistoryMentionCorrect(List<MultiNodeKey> bestDecoding, List<MultiNodeKey> bestGold) {
        for (int i = 0; i < bestDecoding.size(); i++) {
            boolean dIsMention = !bestDecoding.get(i).getCombinedType().equals(ClassAlphabet.noneOfTheAboveClass);
            boolean gIsMention = !bestGold.get(i).getCombinedType().equals(ClassAlphabet.noneOfTheAboveClass);

            // If one mention prediction is not the same, do not allow update.
            if (dIsMention != gIsMention) {
                return false;
            }
        }

        // If all mention prediction are the same, allow update.
        return true;
    }

    private boolean updateWithFullHistoryTypeCorrect(List<MultiNodeKey> bestDecoding, List<MultiNodeKey> bestGold) {
        for (int i = 0; i < bestDecoding.size(); i++) {
//            if (!bestDecoding.get(i).getCombinedType().equals(bestGold.get(i).getCombinedType())) {
//                return false;
//            }

            boolean hasSameType = false;

            for (NodeKey nodeKey : bestDecoding.get(i)) {
                for (NodeKey goldKey : bestGold.get(i)) {
                    if (nodeKey.getMentionType().equals(goldKey.getMentionType())) {
                        hasSameType = true;
                    }
                }
            }

            if (!hasSameType) {
                return false;
            }

        }
        return true;
    }


    private boolean updateWithFullHistoryNonInvention(List<MultiNodeKey> bestDecoding, List<MultiNodeKey> bestGold) {
        for (int i = 0; i < bestDecoding.size(); i++) {
            boolean dIsMention = !bestDecoding.get(i).getCombinedType().equals(ClassAlphabet.noneOfTheAboveClass);
            boolean gIsMention = !bestGold.get(i).getCombinedType().equals(ClassAlphabet.noneOfTheAboveClass);

            // If we invent any mention, do not update.
            if (dIsMention && !gIsMention) {
                return false;
            }
        }
        return true;
    }

    private void addLoss(String name, double loss) {
//        logger.debug("Adding loss " + loss + " for " + name);
        allLoss.adjustValue(name, loss);
    }

    public TObjectDoubleMap<String> update() {
        // Update and then clear accumulated stuff.
        updateInternal(usePa);

        // Logging the loss.
        TObjectDoubleMap<String> currentLoss = new TObjectDoubleHashMap<>();
        currentLoss.putAll(allLoss);

        for (String name : modelNames) {
            allDelta.put(name, newDelta(name));
            allLoss.put(name, 0);
        }
        return currentLoss;
    }

    private void updateInternal(boolean paUpdate) {
        GraphFeatureVector corefDelta = allDelta.get(COREF_MODEL_NAME);
        GraphFeatureVector mentionDelta = allDelta.get(TYPE_MODEL_NAME);

        double totalLoss = 0;

        // So when we update one model, we will only add the loss on its part, which literally ignore the other one.
        if (updateCoref) {
            totalLoss += allLoss.get(COREF_MODEL_NAME);
            logger.debug("Coref loss is " + allLoss.get(COREF_MODEL_NAME));
        }
        if (updateMention) {
            totalLoss += allLoss.get(TYPE_MODEL_NAME);
            logger.debug("Type loss is " + allLoss.get(TYPE_MODEL_NAME));
        }

        if (totalLoss != 0) {
            double tau = defaultStepSize;

//            double tau_coref = defaultStepSize;
//
//            double tau_mention = defaultStepSize;

            boolean isValid = true;
            boolean isCorefValid = true;
            boolean isMentionValid = true;

            if (paUpdate) {
                double corefSquare = updateCoref ? corefDelta.getFeatureSquare() : 0;
                double mentionSquare = updateMention ? mentionDelta.getFeatureSquare() : 0;
                double totalFeatureSquare = corefSquare + mentionSquare;

                tau = totalLoss / totalFeatureSquare;

//                tau_coref = allLoss.get(COREF_MODEL_NAME) / corefSquare;
//                tau_mention = allLoss.get(TYPE_MODEL_NAME) / mentionSquare;

                if (totalFeatureSquare == 0) {
                    //Make sure we don't update when the features are the same, but decoding results are different.
                    isValid = false;
                }

                if (corefSquare == 0) {
                    isCorefValid = false;
                }

                if (mentionSquare == 0) {
                    isMentionValid = false;
                }

                logger.debug("Mention L2 Square is " + mentionSquare + " coref L2 Square is " + corefSquare);
                logger.debug("Total loss is " + totalLoss + ", L2 square is " + totalFeatureSquare);
            }

            logger.debug("Update with step size " + tau);

            if (usePAI && tau > aggressiveness) {
                logger.debug("Choose to use smaller step " + aggressiveness);
                tau = aggressiveness;
            }

            if (isValid) {
                if (updateCoref) {
                    GraphWeightVector corefWeights = allWeights.get(COREF_MODEL_NAME);
                    corefWeights.updateWeightsBy(corefDelta, tau);
                    corefWeights.updateAverageWeights();

                    if (logger.isDebugEnabled()) {
                        logger.debug("Coreference delta is:");
//                    logger.debug(corefDelta.readableNodeVector());
                        logger.debug(corefDelta.matchNodeVector("MentionDistance"));
                        logger.debug("Number of coreference update is " + numCorefUpdates);
                    }
                }

                if (updateMention) {
                    GraphWeightVector mentionWeights = allWeights.get(TYPE_MODEL_NAME);
                    mentionWeights.updateWeightsBy(mentionDelta, tau);
                    mentionWeights.updateAverageWeights();

//                    logger.debug("Mention delta is:");
//                    logger.debug(mentionDelta.readableNodeVector());
                }
            }
//            if (updateCount > 11) {
//                debugger = true;
//                logger.debug("Update count is " + updateCount);
//                DebugUtils.pause(logger);
//            }
        }

        numCorefUpdates = 0;

//        DebugUtils.pause(logger);
    }

    private GraphFeatureVector newDelta(String name) {
        return new GraphFeatureVector(classAlphabets.get(name), featureAlphabets.get(name));
    }

}
