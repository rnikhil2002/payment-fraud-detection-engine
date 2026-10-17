package com.nikhilr.fraud.model;

/** A graph rule that fired, with the weight it added and a human readable reason. */
public record RuleHit(String rule, double weight, String reason) {
}
