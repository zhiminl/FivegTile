package com.android.internal.telephony;

interface ITelephony {
    long getAllowedNetworkTypesForReason(int subId, int reason);
    boolean setAllowedNetworkTypesForReason(int subId, int reason, long allowedNetworkTypes, String callingPackage);
}