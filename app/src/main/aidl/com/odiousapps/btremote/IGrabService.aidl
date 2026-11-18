package com.odiousapps.btremote;

import com.odiousapps.btremote.IMediaKeyHandler;

interface IGrabService {
    // Shizuku calls this transaction code to stop the service
    void destroy() = 16777114;

    String status() = 1;

    // Where media keys go when no app has a media session to receive them
    void setMediaKeyHandler(IMediaKeyHandler handler) = 2;
}
