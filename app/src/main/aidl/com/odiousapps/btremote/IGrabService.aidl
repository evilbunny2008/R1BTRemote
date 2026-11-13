package com.odiousapps.btremote;

interface IGrabService {
    // Shizuku calls this transaction code to stop the service
    void destroy() = 16777114;

    String status() = 1;
}
