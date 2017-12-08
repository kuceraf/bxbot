package com.gazbert.bxbot.strategies.pokus;

import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.strategy.api.TradingStrategy;
import com.gazbert.bxbot.trading.api.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DEFINITIONS:
 * Market order
 * An investor makes a market order through a brokerage service to buy or sell an investment immediately at the best available current price.
 *
 * Order book
 * An order book is an electronic list of buy and sell orders for a specific security or financial instrument, organized by price level.
 * The order book lists the number of shares being bid or offered at each price point
 */

public class PokusStrategy implements TradingStrategy {
    private static final Logger LOG = LogManager.getLogger();

    /**
     * Reference to the main Trading API.
     */
    private TradingApi tradingApi;

    /**
     * The market this strategy is trading on.
     */
    private Market market;
    private long executionRound = 1;
    private final String lineSeparator = System.getProperty("line.separator");
    private OrderState lastOrder;
    //key = orderId
    private Map<String, OrderState> orderStateMap = new HashMap();
    /** MOJE KONSTANTY **/
    // TODO nacitat je z {project-root}/config/
    /**
     * Pro maket BTC/EUR je to 10EUR (COUNTER_CURRENCY_BUY_ORDER_AMOUNT = 10)
     *
     * Napr:
     * COUNTER_CURRENCY_BUY_ORDER_AMOUNT = 10
     * Pri nakupu na marketu BTC/EUR definuji, ze chci nakupovat BTC za 10 EUR. Pro realizaci nakupu udelam vypocet:
     * BTC buy order = 10 EUR / (kolik EUR stoji 1 BTC - tj cena BTC/EUR)
     *
     * Mam 10 EUR
     * Chci X BTC
     * Cena 7,599.99 EUR za 1 BTC (nakupni cena instrumentu BTC/EUR = 7,599.99 EUR)
     * X = 10 / 7,599.99
     * X = 0,00131579 BTC
     * Za 10 EUR si mohu koupit 0,00131579 BTC pri cene BTC/EUR = 7,599.99.
     */
    final private BigDecimal COUNTER_CURRENCY_BUY_ORDER_AMOUNT = BigDecimal.valueOf(200);

    /**
     * The minimum % gain was to achieve before placing a SELL oder.
     */
    final private BigDecimal MINIMUM_PERCENTAGE_GAIN = BigDecimal.valueOf(0.02);

    /**
     * Initialises the Trading Strategy.
     * Called once by the Trading Engine when the bot starts up; it's a bit like a servlet init() method.
     *
     * @param tradingApi the Trading API. Use this to make trades and stuff.
     * @param market     the market for this strategy. This is the market the strategy is currently running on - you wire
     *                   this up in the markets.xml and strategies.xml files.
     * @param config     configuration for the strategy. Contains any (optional) config you set up in the
     *                   strategies.xml file.
     */
    @Override
    public void init(TradingApi tradingApi, Market market, StrategyConfig config) {
        LOG.info(() -> "Initialising Trading Strategy...");
        this.tradingApi = tradingApi;
        this.market = market;
        LOG.info(() -> "Trading Strategy initialised successfully!");
    }

    @Override
    public void execute() throws StrategyException {
        LOG.info("Running on market {} ({} times)", market.getName(), executionRound);

        try {
            // Grab the latest order book for the market.
            final MarketOrderBook orderBook = tradingApi.getMarketOrders(market.getId());
//            LOG.info("Latest order book: {} {}", lineSeparator, orderBook);

            final List<MarketOrder> buyOrders = orderBook.getBuyOrders();
            if (buyOrders.size() == 0) {
                LOG.warn("Exchange returned empty Buy Orders. Ignoring this trade window. OrderBook: " + orderBook);
                return;
            }

            final List<MarketOrder> sellOrders = orderBook.getSellOrders();
            if (sellOrders.size() == 0) {
                LOG.warn("Exchange returned empty Sell Orders. Ignoring this trade window. OrderBook: " + orderBook);
                return;
            }

            // Get the current BID and ASK spot prices.
            final BigDecimal currentBidPrice = buyOrders.get(0).getPrice();
            final BigDecimal currentAskPrice = sellOrders.get(0).getPrice();

            LOG.info(() -> market.getName() + " Highest Current BID price (buy order) = " +
                    new DecimalFormat("#.########").format(currentBidPrice));
            LOG.info(() -> market.getName() + " Lowest Current ASK price (sell order) = " +
                    new DecimalFormat("#.########").format(currentAskPrice));

            if(lastOrder == null) {
                // zaciname - musime nejdrive nakoupit
                executeFirstTimeBuyOrder(currentBidPrice);
            }

            switch (lastOrder.type) {
                case BUY:
                    // umistili jsme pozadavek na nakup - zkusime prodej se ziskem
                    tryPlaceSellOrder();
                    break;
                case SELL:
                    // co jsme nakoupili je prodano - nakoupime znovu
                    tryPlaceBuyOrder(currentBidPrice, currentAskPrice);
                    break;
                default:
            }

        }  catch (ExchangeNetworkException e) {
            // Your timeout handling code could go here.
            // We are just going to log it and swallow it, and wait for next trade cycle.
            LOG.error(market.getName() + " Failed to get market orders because Exchange threw network exception. " +
                    "Waiting until next trade cycle.", e);

        } catch (TradingApiException e) {
            // Your error handling code could go here...
            // We are just going to re-throw as StrategyException for engine to deal with - it will shutdown the bot.
            LOG.error(market.getName() + " Failed to get market orders because Exchange threw TradingApi exception. " +
                    " Telling Trading Engine to shutdown bot!", e);
            throw new StrategyException(e);
        }
        executionRound++;
    }

    /**
     * <p>
     * Algo for executing when last order we placed on the exchange was a SELL.
     * </p>
     * <p>
     * If last sell order filled, we send a new buy order to the exchange.
     * </p>
     *
     * @param currentBidPrice the current market BID price.
     * @param currentAskPrice the current market ASK price.
     * @throws StrategyException if an unexpected exception is received from the Exchange Adapter.
     *                           Throwing this exception indicates we want the Trading Engine to shutdown the bot.
     */
    private void tryPlaceBuyOrder(BigDecimal currentBidPrice, BigDecimal currentAskPrice) throws StrategyException {
        // TODO nepouzit - https://docs.spring.io/spring/docs/1.2.x/javadoc-api/org/springframework/util/Assert.html?
        if(lastOrder.type == OrderType.BUY){
            throw new StrategyException("Wrong strategy execution flow - two successive BUY orders");
        }

        try {
            if (isLastOrderFilled()) {

                LOG.info("^^^ Yay!!! Last SELL order (Id:{}) filled at {}",
                        lastOrder.id,
                        lastOrder.price);
                // Prodej by realizovat - muzeme znovu nakoupit
                doBuyOrder(currentBidPrice);
            } else {

                /*
                 * SELL order not filled yet.
                 * Could be nobody has jumped on it yet... or the order is only part filled... or market has gone down
                 * and we've been undercut and have a stuck sell order. In which case, we have to wait for market to
                 * recover for the order to fill... or you could tweak this code to cancel the current order and lower your ask
                 * (snizit cenu za kterou prodavam)
                 * - remember to deal with any part-filled orders!
                 */

                // Zatim se neuskutecnil pozadavek na prodej - zalogujeme si duvod proc a cekame do dalsiho kola
                if (currentAskPrice.compareTo(lastOrder.price) < 0) {
                    // Cena za kterou prodavame (last order price - nase ASK price) je vyssi nez cena na burze (Current ask price)
                    LOG.info("{} <<< Current ask price {} is LOWER then last SELL order price {} - holding last SELL order...",
                            market.getName(),
                            currentAskPrice,
                            lastOrder.price);
                } else if (currentAskPrice.compareTo(lastOrder.price) > 0) {
                    // Prodavame pod cenou - melo by uz byt prodano
                    LOG.error( "{} >>> Current ask price {} is HIGHER than last SELL order price {} - IMPOSSIBLE! BX-bot must have sold?????",
                            market.getName(),
                            currentAskPrice,
                            lastOrder.price);

                } else if (currentAskPrice.compareTo(lastOrder.price) == 0) {
                    LOG.info("{} === Current ask price {} is EQUAL to last order price {} - holding last SELL order...",
                            market.getName(),
                            currentAskPrice,
                            lastOrder.price);
                }
            }
        } catch (ExchangeNetworkException e) {
            // Your timeout handling code could go here, e.g. you might want to check if the order actually
            // made it to the exchange? And if not, resend it...
            // We are just going to log it and swallow it, and wait for next trade cycle.
            LOG.error(market.getName() + " New Order to BUY base currency failed because Exchange threw network exception. " +
                    "Waiting until next trade cycle. Last Order: " + lastOrder, e);

        } catch (TradingApiException e) {
            // Your error handling code could go here...
            // We are just going to re-throw as StrategyException for engine to deal with - it will shutdown the bot.
            LOG.error(market.getName() + " New order to BUY base currency failed because Exchange threw TradingApi exception. " +
                    " Telling Trading Engine to shutdown bot! Last Order: " + lastOrder, e);
            throw new StrategyException(e);
        }
    }

    /**
     * <p>
     * Algo for executing when last order we placed on the exchanges was a BUY.
     * </p>
     * <p>
     * If last buy order filled, we try and sell at a profit.
     * </p>
     *
     * @throws StrategyException if an unexpected exception is received from the Exchange Adapter.
     *                           Throwing this exception indicates we want the Trading Engine to shutdown the bot.
     */
    private void tryPlaceSellOrder() throws StrategyException {
        // TODO nepouzit - https://docs.spring.io/spring/docs/1.2.x/javadoc-api/org/springframework/util/Assert.html?
        if(lastOrder.type == OrderType.SELL){
            throw new StrategyException("Wrong strategy execution flow - two successive SELL orders");
        }

        try {
            if (isLastOrderFilled()) {
                // Posledni BUY order za nami stanovenou cenu (BID price) se uskutecnil, ted to chci zas prodat se ziskem
                LOG.info("^^^ Yay!!! Last BUY order (Id:{}) filled at {}",
                        lastOrder.id,
                        lastOrder.price);
                /*
                 * The last buy order was filled, so lets see if we can send a new sell order.
                 *
                 * IMPORTANT - new sell order ASK price must be > (last order price + exchange fees) because:
                 *
                 * 1. If we put sell amount in as same amount as previous buy, the exchange barfs because we don't have
                 *    enough units to cover the transaction fee.
                 * 2. We could end up selling at a loss.
                 *
                 * For this example strategy, we're just going to add 2% (taken from the 'minimum-percentage-gain'
                 * config item in the {project-root}/config/strategies.xml config file) on top of previous bid price
                 * to make a little profit and cover the exchange fees.
                 *
                 * Your algo will have other ideas on how much profit to make and when to apply the exchange fees - you
                 * could try calling the TradingApi#getPercentageOfBuyOrderTakenForExchangeFee() and
                 * TradingApi#getPercentageOfSellOrderTakenForExchangeFee() when calculating the order to send to the
                 * exchange...
                 */
                LOG.info("{} Percentage profit (in decimal) to make for the sell order is: {}",
                        market.getName(),
                        MINIMUM_PERCENTAGE_GAIN);

                final BigDecimal amountToAdd = lastOrder.price.multiply(MINIMUM_PERCENTAGE_GAIN);
                LOG.info("{} Amount to add to last buy order fill price: {}",
                        market.getName(),
                        amountToAdd);

                /*
                 * Most exchanges (if not all) use 8 decimal places.
                 * It's usually best to round up the ASK price in your calculations to maximise gains.
                 *
                 * FKU: The ask price represents the minimum price that a seller or sellers are willing to receive for the security.
                 */
                final BigDecimal newAskPrice = lastOrder.price.add(amountToAdd).setScale(8, RoundingMode.HALF_UP);
                LOG.info("{} Placing new SELL order at ask price {}",
                        market.getName(),
                        new DecimalFormat("#.########").format(newAskPrice));

                LOG.info("{} Sending new SELL order to exchange --->",
                        market.getName());

                // Build the new sell order
                String orderId = tradingApi.createOrder(market.getId(), OrderType.SELL, lastOrder.amount, newAskPrice);
                LOG.info("{} New SELL Order sent successfully. ID: {}",
                        market.getName(),
                        lastOrder.id);

                OrderState orderState = new OrderState();
                // update last order state
                orderState.id = orderId;
                orderState.price = newAskPrice;
                orderState.type = OrderType.SELL;
                orderState.amount = lastOrder.amount;
                orderStateMap.put(orderId, orderState);
                lastOrder = orderState;
            } else {
                /*
                 * BUY order has not filled yet.
                 * Could be nobody has jumped on it yet... or the order is only part filled... or market has gone up and
                 * we've been outbid and have a stuck buy order. In which case, we have to wait for the market to
                 * fall for the order to fill... or you could tweak this code to cancel the current order and raise your bid -
                 * remember to deal with any part-filled orders!
                 */

                // Zatim se neuskutecnil posledni pozadavek na nakup - cekame do dalsiho kola
                LOG.info("{} !!! Still have BUY Order {} waiting to fill at {} holding last BUY order...",
                        market.getName(),
                        lastOrder.id,
                        lastOrder.price);
            }
        } catch (ExchangeNetworkException e) {
            // Your timeout handling code could go here, e.g. you might want to check if the order actually
            // made it to the exchange? And if not, resend it...
            // We are just going to log it and swallow it, and wait for next trade cycle.
            LOG.error(market.getName() + " New Order to SELL base currency failed because Exchange threw network exception. " +
                    "Waiting until next trade cycle. Last Order: " + lastOrder, e);

        } catch (TradingApiException e) {
            // Your error handling code could go here...
            // We are just going to re-throw as StrategyException for engine to deal with - it will shutdown the bot.
            LOG.error(market.getName() + " New order to SELL base currency failed because Exchange threw TradingApi exception. " +
                    " Telling Trading Engine to shutdown bot! Last Order: " + lastOrder, e);
            throw new StrategyException(e);
        }

    }

    /**
     * Algo for executing when the Trading Strategy is invoked for the first time.
     * We start off with a buy order at current BID price.
     *
     * @param currentBidPrice the current market BID price. (A bid price is the price a buyer is willing to pay for a security)
     * @throws StrategyException if an unexpected exception is received from the Exchange Adapter.
     *                           Throwing this exception indicates we want the Trading Engine to shutdown the bot.
     */
    private void executeFirstTimeBuyOrder(BigDecimal currentBidPrice) throws StrategyException {
        LOG.info("{} First time order - placing new BUY order at {}",
                () -> market.getName(),
                () -> new DecimalFormat("#.########").format(currentBidPrice));

        try {
            doBuyOrder(currentBidPrice);
        } catch (TradingApiException e) {
            // Your error handling code could go here...
            // We are just going to re-throw as StrategyException for engine to deal with - it will shutdown the bot.
            LOG.error(market.getName() + " Initial order to BUY base currency failed because Exchange threw TradingApi exception. " +
                    " Telling Trading Engine to shutdown bot!", e);
            throw new StrategyException(e);
        } catch (ExchangeNetworkException e) {
            // Your timeout handling code could go here, e.g. you might want to check if the order actually
            // made it to the exchange? And if not, resend it...
            // We are just going to log it and swallow it, and wait for next trade cycle.
            LOG.error(market.getName() + " Initial order to BUY base currency failed because Exchange threw network exception. " +
                    "Waiting until next trade cycle.", e);
        }
    }

    /**
     * Zjisti jestli byl dokoncen posledni pozadavek zaslany na burzu.
     * Pozadavek mohl byt typu BUY nebo SELL a pokud byla cena v pozadavku BUY prilis nizka nebo SELL prilis
     * vysoka pozadavek zustava na burze neuskutecnen.
     * @return true, pokud byl pozadavek uskutecnen. false pokud nebyl
     * @throws ExchangeNetworkException viz {@link TradingApi#getYourOpenOrders} doc
     * @throws TradingApiException viv {@link TradingApi#getYourOpenOrders} doc
     */
    private boolean isLastOrderFilled() throws ExchangeNetworkException, TradingApiException {
        // Fetch our current open orders and see if the buy order is still outstanding/open on the exchange
        final List<OpenOrder> myOrders = tradingApi.getYourOpenOrders(market.getId());
        boolean lastOrderFound = false;
        for (final OpenOrder myOrder : myOrders) {
            if (myOrder.getId().equals(lastOrder.id)) {
                lastOrderFound = true;
                break;
            }
        }
        // If the order is not there, it must be filled.
        return !lastOrderFound;
    }


    /**
     * Vypocita pocet kusu k nakupu a umisti objednavku na burzu
     * @param currentBidPrice cena za kterou jen nakupujici ochoten nakoupit
     *                        (pocet kusu a cena kterou jsem ochoten zaplatit je soucasti objednavky na burze)
     * @throws TradingApiException viz {@link TradingApi#getYourOpenOrders} doc
     * @throws ExchangeNetworkException viz {@link TradingApi#getYourOpenOrders} doc
     */
    private void doBuyOrder(BigDecimal currentBidPrice) throws TradingApiException, ExchangeNetworkException {
        // Calculate the amount of base currency (BTC) to buy for given amount of counter currency (EUR).
        final BigDecimal amountOfBaseCurrencyToBuy = getAmountOfBaseCurrencyToBuyForGivenCounterCurrencyAmount(COUNTER_CURRENCY_BUY_ORDER_AMOUNT);

        // Send the order to the exchange
        LOG.info("{} Sending BUY order to exchange --->",
                market.getName());

        String orderId = tradingApi.createOrder(market.getId(), OrderType.BUY, amountOfBaseCurrencyToBuy, currentBidPrice);
        OrderState orderState = new OrderState();
        // TODO log it into MongoDB
        LOG.info("{} BUY Order sent successfully. ID: {}",
                market.getName(),
                orderId);

        // update last order details
        orderState.id = orderId;
        orderState.price = currentBidPrice;
        orderState.type = OrderType.BUY;
        orderState.amount = amountOfBaseCurrencyToBuy;
        orderStateMap.put(orderId, orderState);
        lastOrder = orderState;
    }

    /**
     * Returns amount of base currency (BTC) to buy for a given amount of counter currency (EUR) based on last
     * market trade price.
     *
     * @param amountOfCounterCurrencyToTrade the amount of counter currency (EUR) we have to trade (buy) with.
     * @return the amount of base currency (BTC) we can buy for the given counter currency (EUR) amount.
     * @throws TradingApiException      if an unexpected error occurred contacting the exchange.
     * @throws ExchangeNetworkException if a request to the exchange has timed out.
     */
    private BigDecimal getAmountOfBaseCurrencyToBuyForGivenCounterCurrencyAmount(BigDecimal amountOfCounterCurrencyToTrade) throws
            TradingApiException, ExchangeNetworkException {

        LOG.info("{} Calculating amount of base currency (BTC) to buy for amount of counter currency {} {}",
                market.getName(),
                new DecimalFormat("#.########").format(amountOfCounterCurrencyToTrade),
                market.getCounterCurrency());

        // Fetch the last trade price
        final BigDecimal lastTradePriceInEurForOneBtc = tradingApi.getLatestMarketPrice(market.getId());

        LOG.info("{} Last trade price for 1 {} was {} {}",
                market.getName(),
                market.getBaseCurrency(),
                new DecimalFormat("#.########").format(lastTradePriceInEurForOneBtc),
                market.getCounterCurrency()
                );
        /*
         * Most exchanges (if not all) use 8 decimal places and typically round in favour of the exchange.
         * It's usually safest to round down the order quantity in your calculations.
         */
        final BigDecimal amountOfBaseCurrencyToBuy = amountOfCounterCurrencyToTrade.divide(
                lastTradePriceInEurForOneBtc, 8, RoundingMode.HALF_DOWN);


        LOG.info("{} Amount of base currency ({}) to BUY for {} {} based on last market trade price: {} ({} / {})",
                market.getName(),
                market.getBaseCurrency(),
                amountOfCounterCurrencyToTrade,
                market.getCounterCurrency(),
                new DecimalFormat("#.########").format(amountOfBaseCurrencyToBuy),
                amountOfCounterCurrencyToTrade,
                new DecimalFormat("#.########").format(lastTradePriceInEurForOneBtc)

        );

        return amountOfBaseCurrencyToBuy;
    }
}
