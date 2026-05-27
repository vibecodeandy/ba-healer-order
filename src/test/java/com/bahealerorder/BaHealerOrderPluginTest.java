package com.bahealerorder;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class BaHealerOrderPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(BaHealerOrderPlugin.class);
        RuneLite.main(args);
    }
}