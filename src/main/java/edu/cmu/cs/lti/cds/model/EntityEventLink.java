package edu.cmu.cs.lti.cds.model;

public class EntityEventLink {
  public String linkType = "";

  public double weight = 0;

  public EntityEventLink(String linkType, double weight) {
    this.linkType = linkType;
    this.weight = weight;
  }
}
