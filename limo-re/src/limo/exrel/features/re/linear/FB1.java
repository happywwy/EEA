package limo.exrel.features.re.linear;

import limo.core.Sentence;
import limo.core.Mention;
import limo.core.Relation;
import limo.core.trees.constituency.ParseTree;

//Fore-Between. Tokens before and between the two entities (unigrams only)
public class FB1 extends RelationExtractionLinearFeature {

	@Override
	protected String _extract(ParseTree parseTree, Mention mention1, Mention mention2,
			Relation relation, Sentence sentence, String groupId) {
		
		int[] tokens1 = mention1.getTokenIds();
		int[] tokens2 = mention2.getTokenIds();
		
		int startM1 = tokens1[0];
		int endM1 = tokens1[tokens1.length-1];
		
		int startM2 = tokens2[0];
		
		StringBuilder sb = new StringBuilder();
		int i=0;
		if (startM1==0)
			i=endM1+1;
		
		while (i < startM2) {
		
			if (i>endM1 || i < startM1) {
				sb.append(sentence.getTokens().get(i).getValue());
				sb.append(RelationExtractionLinearFeature.BOWseparator);
			}
			i++;
		} 
		return sb.toString();
		
	}

}
