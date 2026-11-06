package com.esep.account;

public enum AccountType {
    /** Belongs to a real customer, balance can never go below zero. */
    USER,
    /** Technical funding account for deposits/withdrawals, may go negative. */
    SYSTEM
}
