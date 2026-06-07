package com.the3Cgrp.zupptrade.agent2.client;

import com.the3Cgrp.zupptrade.agent2.client.model.OptionChainData;

import java.time.LocalDate;

public interface OptionChainClient {

    OptionChainData fetch(LocalDate expiryDate);
}
