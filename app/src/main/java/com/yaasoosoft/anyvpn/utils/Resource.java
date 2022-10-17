package com.yaasoosoft.anyvpn.utils;

import java.io.Closeable;

public class Resource {
    public static void closeResources(Closeable... resources)
    {
        for (Closeable resource : resources)
        {
            try
            {
                resource.close();
            }
            catch (Exception e)
            {
                // Ignore
            }
        }
    }
}
