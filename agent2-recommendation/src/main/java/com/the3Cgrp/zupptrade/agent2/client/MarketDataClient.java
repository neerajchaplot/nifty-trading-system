package com.the3Cgrp.zupptrade.agent2.client;

import com.the3Cgrp.zupptrade.agent2.client.model.MarketSnapshot;

public interface MarketDataClient {

    MarketSnapshot fetchSnapshot();
}
