/*
 * Copyright 2011-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Currency;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.Fiat;
import org.bitcoinj.utils.MonetaryFormat;
import org.json.JSONObject;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;
import com.squareup.okhttp.Call;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.util.GenericUtils;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.text.format.DateUtils;

/**
 * @author Andreas Schildbach
 */
public class ExchangeRatesProvider extends ContentProvider {

    public static final String KEY_CURRENCY_CODE = "currency_code";
    private static final String KEY_RATE_COIN = "rate_coin";
    private static final String KEY_RATE_FIAT = "rate_fiat";
    private static final String KEY_SOURCE = "source";

    public static final String QUERY_PARAM_Q = "q";
    private static final String QUERY_PARAM_OFFLINE = "offline";

    private Configuration config;
    private String userAgent;

    @Nullable
    private Map<String, ExchangeRate> exchangeRates = null;
    private long lastUpdated = 0;
	private double gltBtcConversion = -1;
	private double gltDogeConversion = -1;
	private double gltEsp2Conversion = -1;
	private double gltKicConversion = -1;
	private double gltLtcConversion = -1;
	private double gltMoonConversion = -1;

    private static final HttpUrl BITCOINAVERAGE_URL = HttpUrl
            .parse("https://apiv2.bitcoinaverage.com/indices/global/ticker/short?crypto=BTC");
    private static final String BITCOINAVERAGE_SOURCE = "BitcoinAverage.com";
	
    private static final HttpUrl COINEXCHANGE_URL = HttpUrl
            .parse("https://www.coinexchange.io/api/v1/getmarketsummary?market_id=263");
	private static final String COINEXCHANGE_SOURCE = "Coinexchange.io";
	
	private static final HttpUrl NOVAEXCHANGE_URL_DOGE = HttpUrl
            .parse("https://novaexchange.com/remote/v2/market/info/DOGE_GLT/");
	private static final String NOVAEXCHANGE_SOURCE_DOGE = "Novaexchange.com";
	
	private static final HttpUrl NOVAEXCHANGE_URL_ESP2 = HttpUrl
            .parse("https://novaexchange.com/remote/v2/market/info/ESP2_GLT/");
	private static final String NOVAEXCHANGE_SOURCE_ESP2 = "Novaexchange.com";
	
	private static final HttpUrl NOVAEXCHANGE_URL_KIC = HttpUrl
            .parse("https://novaexchange.com/remote/v2/market/info/KIC_GLT/");
	private static final String NOVAEXCHANGE_SOURCE_KIC = "Novaexchange.com";
	
	private static final HttpUrl NOVAEXCHANGE_URL_LTC = HttpUrl
            .parse("https://novaexchange.com/remote/v2/market/info/LTC_GLT/");
	private static final String NOVAEXCHANGE_SOURCE_LTC = "Novaexchange.com";
	
	private static final HttpUrl NOVAEXCHANGE_URL_MOON = HttpUrl
            .parse("https://novaexchange.com/remote/v2/market/info/MOON_GLT/");
	private static final String NOVAEXCHANGE_SOURCE_MOON = "Novaexchange.com";

    private static final long UPDATE_FREQ_MS = 10 * DateUtils.MINUTE_IN_MILLIS;

    private static final Logger log = LoggerFactory.getLogger(ExchangeRatesProvider.class);

    @Override
    public boolean onCreate() {
        if (!Constants.ENABLE_EXCHANGE_RATES)
            return false;

        final Context context = getContext();

        this.config = new Configuration(PreferenceManager.getDefaultSharedPreferences(context), context.getResources());
        this.userAgent = WalletApplication.httpUserAgent(WalletApplication.packageInfoFromContext(context).versionName);

        final ExchangeRate cachedExchangeRate = config.getCachedExchangeRate();
        if (cachedExchangeRate != null) {
            exchangeRates = new TreeMap<String, ExchangeRate>();
            exchangeRates.put(cachedExchangeRate.getCurrencyCode(), cachedExchangeRate);
        }

        return true;
    }

    public static Uri contentUri(final String packageName, final boolean offline) {
        final Uri.Builder uri = Uri.parse("content://" + packageName + '.' + "exchange_rates").buildUpon();
        if (offline)
            uri.appendQueryParameter(QUERY_PARAM_OFFLINE, "1");
        return uri.build();
    }

    @Override
    public Cursor query(final Uri uri, final String[] projection, final String selection, final String[] selectionArgs,
            final String sortOrder) {
        final long now = System.currentTimeMillis();

        final boolean offline = uri.getQueryParameter(QUERY_PARAM_OFFLINE) != null;

        if (!offline && (lastUpdated == 0 || now - lastUpdated > UPDATE_FREQ_MS)) {
			double newGltBtcConversion = -1;
			double newGltDogeConversion = -1;
			double newGltEsp2Conversion = -1;
			double newGltKicConversion = -1;
			double newGltLtcConversion = -1;
			double newGltMoonConversion = -1;
            if ((gltBtcConversion == -1))
                newGltBtcConversion = requestGltBtcConversion();

            if (newGltBtcConversion != -1)
                gltBtcConversion = newGltBtcConversion;

            if (gltBtcConversion == -1)
				return null;
				
			if ((gltDogeConversion == -1))
                newGltDogeConversion = requestGltDogeConversion();

            if (newGltDogeConversion != -1)
                gltDogeConversion = newGltDogeConversion;

            if (gltDogeConversion == -1)
				return null;
				
			if ((gltEsp2Conversion == -1))
                newGltEsp2Conversion = requestGltEsp2Conversion();

            if (newGltEsp2Conversion != -1)
                gltEsp2Conversion = newGltEsp2Conversion;

            if (gltEsp2Conversion == -1)
				return null;
				
			if ((gltKicConversion == -1))
                newGltKicConversion = requestGltKicConversion();

            if (newGltKicConversion != -1)
                gltKicConversion = newGltKicConversion;

            if (gltKicConversion == -1)
				return null;
				
			if ((gltLtcConversion == -1))
                newGltLtcConversion = requestGltLtcConversion();

            if (newGltLtcConversion != -1)
                gltLtcConversion = newGltLtcConversion;

            if (gltLtcConversion == -1)
				return null;
			
			if ((gltMoonConversion == -1))
                newGltMoonConversion = requestGltMoonConversion();

            if (newGltMoonConversion != -1)
                gltMoonConversion = newGltMoonConversion;

            if (gltMoonConversion == -1)
				return null;
				
            Map<String, ExchangeRate> newExchangeRates = null;
            if (newExchangeRates == null)
                newExchangeRates = requestExchangeRates(gltBtcConversion);

            if (newExchangeRates != null) {
				double mBTCRate = gltBtcConversion*1000;
				double satoshirate = gltBtcConversion*1000*1000*100;
				String strBTCRate = String.format(Locale.US, "%.8f", gltBtcConversion).replace(',', '.');
                String strmBTCRate = String.format(Locale.US, "%.6f", mBTCRate).replace(',', '.');
				String strsatoshiRate = String.format(Locale.US, "%.2f", satoshirate).replace(',', '.');
				String strDogeRate = String.format(Locale.US, "%.8f", gltDogeConversion).replace(',', '.');
				String strEsp2Rate = String.format(Locale.US, "%.8f", gltEsp2Conversion).replace(',', '.');
				String strKicRate = String.format(Locale.US, "%.8f", gltKicConversion).replace(',', '.');
				String strLtcRate = String.format(Locale.US, "%.8f", gltLtcConversion).replace(',', '.');
				String strMoonRate = String.format(Locale.US, "%.8f", gltMoonConversion).replace(',', '.');
				
				newExchangeRates.put("BTC", new ExchangeRate(new org.bitcoinj.utils.ExchangeRate(Fiat.parseFiat("BTC", strBTCRate)), COINEXCHANGE_SOURCE));
				newExchangeRates.put("DOGE", new ExchangeRate(new org.bitcoinj.utils.ExchangeRate(Fiat.parseFiat("DOGE", strDogeRate)), NOVAEXCHANGE_SOURCE_DOGE));
				newExchangeRates.put("ESP2", new ExchangeRate(new org.bitcoinj.utils.ExchangeRate(Fiat.parseFiat("ESP2", strEsp2Rate)), NOVAEXCHANGE_SOURCE_ESP2));
				newExchangeRates.put("GLT", new ExchangeRate(new org.bitcoinj.utils.ExchangeRate(Fiat.parseFiat("GLT", "1")), COINEXCHANGE_SOURCE));
				newExchangeRates.put("KIC", new ExchangeRate(new org.bitcoinj.utils.ExchangeRate(Fiat.parseFiat("KIC", strKicRate)), NOVAEXCHANGE_SOURCE_KIC));
				newExchangeRates.put("LTC", new ExchangeRate(new org.bitcoinj.utils.ExchangeRate(Fiat.parseFiat("LTC", strLtcRate)), NOVAEXCHANGE_SOURCE_LTC));
				newExchangeRates.put("MOON", new ExchangeRate(new org.bitcoinj.utils.ExchangeRate(Fiat.parseFiat("MOON", strMoonRate)), NOVAEXCHANGE_SOURCE_MOON));
				newExchangeRates.put("mBTC", new ExchangeRate(new org.bitcoinj.utils.ExchangeRate(Fiat.parseFiat("mBTC", strmBTCRate)), COINEXCHANGE_SOURCE));
				newExchangeRates.put("SATOSHI", new ExchangeRate(new org.bitcoinj.utils.ExchangeRate(Fiat.parseFiat("SATOSHI", strsatoshiRate)), COINEXCHANGE_SOURCE));
                				
                exchangeRates = newExchangeRates;
                lastUpdated = now;

                final ExchangeRate exchangeRateToCache = bestExchangeRate(config.getExchangeCurrencyCode());
                if (exchangeRateToCache != null)
                    config.setCachedExchangeRate(exchangeRateToCache);
            }
        }

        if (exchangeRates == null || gltBtcConversion == -1)
            return null;

        final MatrixCursor cursor = new MatrixCursor(
                new String[] { BaseColumns._ID, KEY_CURRENCY_CODE, KEY_RATE_COIN, KEY_RATE_FIAT, KEY_SOURCE });

        if (selection == null) {
            for (final Map.Entry<String, ExchangeRate> entry : exchangeRates.entrySet()) {
                final ExchangeRate exchangeRate = entry.getValue();
                final org.bitcoinj.utils.ExchangeRate rate = exchangeRate.rate;
                final String currencyCode = exchangeRate.getCurrencyCode();
                cursor.newRow().add(currencyCode.hashCode()).add(currencyCode).add(rate.coin.value).add(rate.fiat.value)
                        .add(exchangeRate.source);
            }
        } else if (selection.equals(QUERY_PARAM_Q)) {
            final String selectionArg = selectionArgs[0].toLowerCase(Locale.US);
            for (final Map.Entry<String, ExchangeRate> entry : exchangeRates.entrySet()) {
                final ExchangeRate exchangeRate = entry.getValue();
                final org.bitcoinj.utils.ExchangeRate rate = exchangeRate.rate;
                final String currencyCode = exchangeRate.getCurrencyCode();
                final String currencySymbol = GenericUtils.currencySymbol(currencyCode);
                if (currencyCode.toLowerCase(Locale.US).contains(selectionArg)
                        || currencySymbol.toLowerCase(Locale.US).contains(selectionArg))
                    cursor.newRow().add(currencyCode.hashCode()).add(currencyCode).add(rate.coin.value)
                            .add(rate.fiat.value).add(exchangeRate.source);
            }
        } else if (selection.equals(KEY_CURRENCY_CODE)) {
            final String selectionArg = selectionArgs[0];
            final ExchangeRate exchangeRate = bestExchangeRate(selectionArg);
            if (exchangeRate != null) {
                final org.bitcoinj.utils.ExchangeRate rate = exchangeRate.rate;
                final String currencyCode = exchangeRate.getCurrencyCode();
                cursor.newRow().add(currencyCode.hashCode()).add(currencyCode).add(rate.coin.value).add(rate.fiat.value)
                        .add(exchangeRate.source);
            }
        }

        return cursor;
    }

    private ExchangeRate bestExchangeRate(final String currencyCode) {
        ExchangeRate rate = currencyCode != null ? exchangeRates.get(currencyCode) : null;
        if (rate != null)
            return rate;

        final String defaultCode = defaultCurrencyCode();
        rate = defaultCode != null ? exchangeRates.get(defaultCode) : null;

        if (rate != null)
            return rate;

        return exchangeRates.get(Constants.DEFAULT_EXCHANGE_CURRENCY);
    }

    private String defaultCurrencyCode() {
        try {
            return Currency.getInstance(Locale.getDefault()).getCurrencyCode();
        } catch (final IllegalArgumentException x) {
            return null;
        }
    }

    public static ExchangeRate getExchangeRate(final Cursor cursor) {
        final String currencyCode = cursor
                .getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_CURRENCY_CODE));
        final Coin rateCoin = Coin
                .valueOf(cursor.getLong(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE_COIN)));
        final Fiat rateFiat = Fiat.valueOf(currencyCode,
                cursor.getLong(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE_FIAT)));
        final String source = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_SOURCE));

        return new ExchangeRate(new org.bitcoinj.utils.ExchangeRate(rateCoin, rateFiat), source);
    }

    @Override
    public Uri insert(final Uri uri, final ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(final Uri uri, final String selection, final String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(final Uri uri) {
        throw new UnsupportedOperationException();
    }

    private Map<String, ExchangeRate> requestExchangeRates(double gltBtcConversion) {
        final Stopwatch watch = Stopwatch.createStarted();

        final Request.Builder request = new Request.Builder();
        request.url(BITCOINAVERAGE_URL);
        request.header("User-Agent", userAgent);

        final Call call = Constants.HTTP_CLIENT.newCall(request.build());
        try {
            final Response response = call.execute();
            if (response.isSuccessful()) {
                final String content = response.body().string();
                final JSONObject head = new JSONObject(content);
                final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();

                for (final Iterator<String> i = head.keys(); i.hasNext();) {
                    final String currencyCode = i.next();
                    if (currencyCode.startsWith("BTC")) {
                        final String fiatCurrencyCode = currencyCode.substring(3);
                        if (!fiatCurrencyCode.equals(MonetaryFormat.CODE_BTC)
                                && !fiatCurrencyCode.equals(MonetaryFormat.CODE_MBTC)
                                && !fiatCurrencyCode.equals(MonetaryFormat.CODE_UBTC)) {
                            final JSONObject exchangeRate = head.getJSONObject(currencyCode);
                            final JSONObject averages = exchangeRate.getJSONObject("averages");
                            try {
                                float value = Float.valueOf(averages.getString("day"));
								String fetchedvalue = String.format("%.02f", value);
								final String rate = fetchedvalue.replace(",", ".");
								final double btcRate = Double.parseDouble(Fiat.parseFiat(fiatCurrencyCode, rate).toPlainString());
                                DecimalFormat df = new DecimalFormat("#.########");
                                df.setRoundingMode(RoundingMode.HALF_UP);
                                DecimalFormatSymbols dfs = new DecimalFormatSymbols();
                                dfs.setDecimalSeparator('.');
                                dfs.setGroupingSeparator(',');
                                df.setDecimalFormatSymbols(dfs);
								final Fiat gltRate = parseFiatInexact(fiatCurrencyCode, df.format(btcRate*gltBtcConversion));
																
                                if (gltRate.signum() > 0)
                                    rates.put(fiatCurrencyCode, new ExchangeRate(
                                            new org.bitcoinj.utils.ExchangeRate(gltRate), BITCOINAVERAGE_SOURCE));
                            } catch (final IllegalArgumentException x) {
                                log.warn("problem fetching {} exchange rate from {}: {}", currencyCode,
                                        BITCOINAVERAGE_URL, x.getMessage());
                            }
                        }
                    }
                }

                watch.stop();
                log.info("fetched exchange rates from {}, {} chars, took {}", BITCOINAVERAGE_URL, content.length(),
                        watch);

                return rates;
            } else {
                log.warn("http status {} when fetching exchange rates from {}", response.code(), BITCOINAVERAGE_URL);
            }
        } catch (final Exception x) {
            log.warn("problem fetching exchange rates from " + BITCOINAVERAGE_URL, x);
        }

        return null;
    }

    // backport from bitcoinj 0.15
    private static Fiat parseFiatInexact(final String currencyCode, final String str) {
        final long val = new BigDecimal(str).movePointRight(Fiat.SMALLEST_UNIT_EXPONENT).longValue();
        return Fiat.valueOf(currencyCode, val);
    }
	
	private double requestGltBtcConversion() {
        final Request.Builder request = new Request.Builder();
        request.url(COINEXCHANGE_URL);
        request.header("User-Agent", userAgent);

        final Call call = Constants.HTTP_CLIENT.newCall(request.build());
        try {
            final Response response = call.execute();
            if (response.isSuccessful()) {
                final String content = response.body().string();
                try {
                    final JSONObject json = new JSONObject(content);
					final JSONObject lastprice = json.getJSONObject("result");
                    boolean success = json.getString("success").equals("1");
                    if (!success) {
                        return -1;
                    }
                    return Double.valueOf(lastprice.getString("LastPrice"));
                } catch (NumberFormatException e) {
                    log.warn("Couldn't get the current exchange rate from coinexchange.");
                    return -1;
                }

            } else {
                log.warn("http status {} when fetching exchange rates from {}", response.code(), COINEXCHANGE_URL);
            }
        } catch (final Exception x) {
            log.warn("problem reading exchange rates", x);
        }

        return -1;
	}
	
	private double requestGltDogeConversion() {
        final Request.Builder request = new Request.Builder();
        request.url(NOVAEXCHANGE_URL_DOGE);
        request.header("User-Agent", userAgent);

        final Call call = Constants.HTTP_CLIENT.newCall(request.build());
        try {
            final Response response = call.execute();
            if (response.isSuccessful()) {
                final String content = response.body().string();
                try {
                    final JSONObject json = new JSONObject(content);
                    boolean success = json.getString("status").equals("success");
					JSONArray traderesult = json.getJSONArray("markets");
					JSONObject finaltraderesult = traderesult.getJSONObject(0);
                    if (!success) {
                        return -1;
                    }
                    return Double.valueOf(finaltraderesult.getString("last_price"));
                } catch (NumberFormatException e) {
                    log.warn("Couldn't get the current exchange rate from novaexchange (DOGE).");
                    return -1;
                }

            } else {
                log.warn("http status {} when fetching exchange rates from {}", response.code(), NOVAEXCHANGE_URL_DOGE);
            }
        } catch (final Exception x) {
            log.warn("problem reading exchange rates", x);
        }

        return -1;
	}
	
	private double requestGltEsp2Conversion() {
        final Request.Builder request = new Request.Builder();
        request.url(NOVAEXCHANGE_URL_ESP2);
        request.header("User-Agent", userAgent);

        final Call call = Constants.HTTP_CLIENT.newCall(request.build());
        try {
            final Response response = call.execute();
            if (response.isSuccessful()) {
                final String content = response.body().string();
                try {
                    final JSONObject json = new JSONObject(content);
                    boolean success = json.getString("status").equals("success");
					JSONArray traderesult = json.getJSONArray("markets");
					JSONObject finaltraderesult = traderesult.getJSONObject(0);
                    if (!success) {
                        return -1;
                    }
                    return Double.valueOf(finaltraderesult.getString("last_price"));
                } catch (NumberFormatException e) {
                    log.warn("Couldn't get the current exchange rate from novaexchange (ESP2).");
                    return -1;
                }

            } else {
                log.warn("http status {} when fetching exchange rates from {}", response.code(), NOVAEXCHANGE_URL_ESP2);
            }
        } catch (final Exception x) {
            log.warn("problem reading exchange rates", x);
        }

        return -1;
	}
	
	private double requestGltKicConversion() {
        final Request.Builder request = new Request.Builder();
        request.url(NOVAEXCHANGE_URL_KIC);
        request.header("User-Agent", userAgent);

        final Call call = Constants.HTTP_CLIENT.newCall(request.build());
        try {
            final Response response = call.execute();
            if (response.isSuccessful()) {
                final String content = response.body().string();
                try {
                    final JSONObject json = new JSONObject(content);
                    boolean success = json.getString("status").equals("success");
					JSONArray traderesult = json.getJSONArray("markets");
					JSONObject finaltraderesult = traderesult.getJSONObject(0);
                    if (!success) {
                        return -1;
                    }
                    return Double.valueOf(finaltraderesult.getString("last_price"));
                } catch (NumberFormatException e) {
                    log.warn("Couldn't get the current exchange rate from novaexchange (KIC).");
                    return -1;
                }

            } else {
                log.warn("http status {} when fetching exchange rates from {}", response.code(), NOVAEXCHANGE_URL_KIC);
            }
        } catch (final Exception x) {
            log.warn("problem reading exchange rates", x);
        }

        return -1;
	}
	
	private double requestGltLtcConversion() {
        final Request.Builder request = new Request.Builder();
        request.url(NOVAEXCHANGE_URL_LTC);
        request.header("User-Agent", userAgent);

        final Call call = Constants.HTTP_CLIENT.newCall(request.build());
        try {
            final Response response = call.execute();
            if (response.isSuccessful()) {
                final String content = response.body().string();
                try {
                    final JSONObject json = new JSONObject(content);
                    boolean success = json.getString("status").equals("success");
					JSONArray traderesult = json.getJSONArray("markets");
					JSONObject finaltraderesult = traderesult.getJSONObject(0);
                    if (!success) {
                        return -1;
                    }
                    return Double.valueOf(finaltraderesult.getString("last_price"));
                } catch (NumberFormatException e) {
                    log.warn("Couldn't get the current exchange rate from novaexchange (LTC).");
                    return -1;
                }

            } else {
                log.warn("http status {} when fetching exchange rates from {}", response.code(), NOVAEXCHANGE_URL_LTC);
            }
        } catch (final Exception x) {
            log.warn("problem reading exchange rates", x);
        }

        return -1;
	}
	
	private double requestGltMoonConversion() {
        final Request.Builder request = new Request.Builder();
        request.url(NOVAEXCHANGE_URL_MOON);
        request.header("User-Agent", userAgent);

        final Call call = Constants.HTTP_CLIENT.newCall(request.build());
        try {
            final Response response = call.execute();
            if (response.isSuccessful()) {
                final String content = response.body().string();
                try {
                    final JSONObject json = new JSONObject(content);
                    boolean success = json.getString("status").equals("success");
					JSONArray traderesult = json.getJSONArray("markets");
					JSONObject finaltraderesult = traderesult.getJSONObject(0);
                    if (!success) {
                        return -1;
                    }
                    return Double.valueOf(finaltraderesult.getString("last_price"));
                } catch (NumberFormatException e) {
                    log.warn("Couldn't get the current exchange rate from novaexchange (MOON).");
                    return -1;
                }

            } else {
                log.warn("http status {} when fetching exchange rates from {}", response.code(), NOVAEXCHANGE_URL_MOON);
            }
        } catch (final Exception x) {
            log.warn("problem reading exchange rates", x);
        }

        return -1;
	}
}