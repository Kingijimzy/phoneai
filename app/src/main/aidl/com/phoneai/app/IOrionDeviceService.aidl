package com.phoneai.app;

/** Narrow IPC surface. Operation names are a fixed enum; no arbitrary shell is accepted. */
interface IOrionDeviceService {
    String execute(String operation, String argument);
}