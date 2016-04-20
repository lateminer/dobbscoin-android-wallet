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

package de.schildbach.wallet.ui;

import javax.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Currency;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.utils.Fiat;

import org.json.JSONObject;
import org.json.JSONException;

import android.app.Activity;
import android.app.Fragment;
import android.app.LoaderManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.TextView;
import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.ExchangeRatesProvider;
import de.schildbach.wallet.ExchangeRatesProvider.ExchangeRate;
import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet.service.BlockchainStateLoader;
import de.schildbach.wallet.ui.send.SendCoinsActivity;

import de.schildbach.wallet.util.GenericUtils;
import de.schildbach.wallet.util.Io;

import subgeneius.dobbs.wallet.R;

/**
 * @author Andreas Schildbach
 */
public final class WalletBalanceFragment extends Fragment
{
	private WalletApplication application;
	private AbstractWalletActivity activity;
	private Configuration config;
	private Wallet wallet;
	private LoaderManager loaderManager;

	private View viewBalance;
	private CurrencyTextView viewBalanceBOB;
	private View viewBalanceTooMuch;
	private CurrencyTextView viewBalanceLocal;
	private TextView viewProgress;

	private boolean showLocalBalance;
	private boolean installedFromGooglePlay;

	@Nullable
	private Coin balance = null;
	@Nullable
	private ExchangeRate exchangeRate = null;
	@Nullable
	private BlockchainState blockchainState = null;

	private static final int ID_BALANCE_LOADER = 0;
	private static final int ID_RATE_LOADER = 1;
	private static final int ID_BLOCKCHAIN_STATE_LOADER = 2;

	private static final long BLOCKCHAIN_UPTODATE_THRESHOLD_MS = DateUtils.HOUR_IN_MILLIS;
    private static final long SATOSHI_IN_BTC = 100000000;
	private static final Coin SOME_BALANCE_THRESHOLD = Coin.COIN.multiply(1000);
	private static final Coin TOO_MUCH_BALANCE_THRESHOLD = Coin.COIN.multiply(2500000);

	@Override
	public void onAttach(final Activity activity)
	{
		super.onAttach(activity);

		this.activity = (AbstractWalletActivity) activity;
		this.application = (WalletApplication) activity.getApplication();
		this.config = application.getConfiguration();
		this.wallet = application.getWallet();
		this.loaderManager = getLoaderManager();

		showLocalBalance = getResources().getBoolean(R.bool.show_local_balance);
		installedFromGooglePlay = "com.android.vending".equals(application.getPackageManager().getInstallerPackageName(application.getPackageName()));
	}

	@Override
	public void onCreate(final Bundle savedInstanceState)
	{
		setHasOptionsMenu(true);

		super.onCreate(savedInstanceState);
	}

	@Override
	public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState)
	{
		return inflater.inflate(R.layout.wallet_balance_fragment, container, false);
	}

	@Override
	public void onViewCreated(final View view, final Bundle savedInstanceState)
	{
		super.onViewCreated(view, savedInstanceState);

		final boolean showExchangeRatesOption = getResources().getBoolean(R.bool.show_exchange_rates_option);

		viewBalance = view.findViewById(R.id.wallet_balance);
		if (showExchangeRatesOption)
		{
			viewBalance.setOnClickListener(new OnClickListener()
			{
				@Override
				public void onClick(final View v)
				{
					startActivity(new Intent(getActivity(), ExchangeRatesActivity.class));
				}
			});
		}
		else
		{
			viewBalance.setEnabled(false);
		}

		viewBalanceBOB = (CurrencyTextView) view.findViewById(R.id.wallet_balance_BOB);
		viewBalanceBOB.setPrefixScaleX(0.9f);

		viewBalanceTooMuch = view.findViewById(R.id.wallet_balance_too_much);

		viewBalanceLocal = (CurrencyTextView) view.findViewById(R.id.wallet_balance_local);
		viewBalanceLocal.setInsignificantRelativeSize(1);
		viewBalanceLocal.setStrikeThru(Constants.TEST);

		viewProgress = (TextView) view.findViewById(R.id.wallet_balance_progress);
	}

	@Override
	public void onResume()
	{
		super.onResume();

		loaderManager.initLoader(ID_BALANCE_LOADER, null, balanceLoaderCallbacks);
		loaderManager.initLoader(ID_RATE_LOADER, null, rateLoaderCallbacks);
		loaderManager.initLoader(ID_BLOCKCHAIN_STATE_LOADER, null, blockchainStateLoaderCallbacks);

		updateView();
	}

	@Override
	public void onPause()
	{
		loaderManager.destroyLoader(ID_BLOCKCHAIN_STATE_LOADER);
		loaderManager.destroyLoader(ID_RATE_LOADER);
		loaderManager.destroyLoader(ID_BALANCE_LOADER);

		super.onPause();
	}

	@Override
	public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater)
	{
		inflater.inflate(R.menu.wallet_balance_fragment_options, menu);

		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public void onPrepareOptionsMenu(final Menu menu)
	{
		final boolean hasSomeBalance = balance != null && !balance.isLessThan(SOME_BALANCE_THRESHOLD);
		menu.findItem(R.id.wallet_balance_options_donate).setVisible(!Constants.TEST && (!installedFromGooglePlay || hasSomeBalance));

		super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item)
	{
		switch (item.getItemId())
		{
			case R.id.wallet_balance_options_donate:
				handleDonate();
				return true;
		}

		return super.onOptionsItemSelected(item);
	}

    public static Object getCoinValueBTC_bittrex()
	{
		Double btcRate = 0.0;
		String currency = "BTC";
		String url = "https://bittrex.com/api/v1.1/public/getticker?market=btc-bob";

		try {
			final URL URL_bittrex = new URL(url);
			final HttpURLConnection connection = (HttpURLConnection)URL_bittrex.openConnection();
			connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS * 2);
			connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS * 2);
			connection.connect();

			final StringBuilder content = new StringBuilder();

			Reader reader = null;
			try
			{
				reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024));
				Io.copy(reader, content);
				final JSONObject head = new JSONObject(content.toString());

				/*
				{"success":true,"message":"","result":{"Bid":0.00313794,"Ask":0.00321785,"Last":0.00315893}}
				}*/
				String result = head.getString("success");
				if(result.equals("true"))
				{
					JSONObject dataObject = head.getJSONObject("result");

					Double averageTrade = dataObject.getDouble("Last");


					if(currency.equalsIgnoreCase("BTC"))
						btcRate = averageTrade;
				}
				return btcRate;
			}
			finally
			{
				if (reader != null)
					reader.close();
			}

		}
		catch (final IOException x)
		{
			x.printStackTrace();
		}
		catch (final JSONException x)
		{
			x.printStackTrace();
		}

		return null;
	}
    
	private void handleDonate()
	{
		try
		{
			SendCoinsActivity.start(activity, PaymentIntent.fromAddress(Constants.DONATION_ADDRESS, getString(R.string.wallet_donate_address_label)));
		}
		catch (final AddressFormatException x)
		{
			// cannot happen, address is hardcoded
			throw new RuntimeException(x);
		}
	}

	private void updateView()
	{
		if (!isAdded())
			return;

		final boolean showProgress;

		if (blockchainState != null && blockchainState.bestChainDate != null)
		{
			final long blockchainLag = System.currentTimeMillis() - blockchainState.bestChainDate.getTime();
			final boolean blockchainUptodate = blockchainLag < BLOCKCHAIN_UPTODATE_THRESHOLD_MS;
			final boolean noImpediments = blockchainState.impediments.isEmpty();

			showProgress = !(blockchainUptodate || !blockchainState.replaying);

			final String downloading = getString(noImpediments ? R.string.blockchain_state_progress_downloading
					: R.string.blockchain_state_progress_stalled);

			if (blockchainLag < 2 * DateUtils.DAY_IN_MILLIS)
			{
				final long hours = blockchainLag / DateUtils.HOUR_IN_MILLIS;
				viewProgress.setText(getString(R.string.blockchain_state_progress_hours, downloading, hours));
			}
			else if (blockchainLag < 2 * DateUtils.WEEK_IN_MILLIS)
			{
				final long days = blockchainLag / DateUtils.DAY_IN_MILLIS;
				viewProgress.setText(getString(R.string.blockchain_state_progress_days, downloading, days));
			}
			else if (blockchainLag < 90 * DateUtils.DAY_IN_MILLIS)
			{
				final long weeks = blockchainLag / DateUtils.WEEK_IN_MILLIS;
				viewProgress.setText(getString(R.string.blockchain_state_progress_weeks, downloading, weeks));
			}
			else
			{
				final long months = blockchainLag / (30 * DateUtils.DAY_IN_MILLIS);
				viewProgress.setText(getString(R.string.blockchain_state_progress_months, downloading, months));
			}
		}
		else
		{
			showProgress = false;
		}

		if (!showProgress)
		{
			viewBalance.setVisibility(View.VISIBLE);

			if (!showLocalBalance)
				viewBalanceLocal.setVisibility(View.GONE);

			if (balance != null)
			{
				viewBalanceBOB.setVisibility(View.VISIBLE);
				viewBalanceBOB.setFormat(config.getFormat());
				viewBalanceBOB.setAmount(balance);

				final boolean tooMuch = balance.isGreaterThan(TOO_MUCH_BALANCE_THRESHOLD);

				viewBalanceTooMuch.setVisibility(tooMuch ? View.VISIBLE : View.GONE);

				if (showLocalBalance)
				{
                    
                    long btcRate = 0;
                    Object result = getCoinValueBTC_bittrex();
            
                    if (result != null)   
					{
                        btcRate = (long)((double)result * (double)SATOSHI_IN_BTC);
                        Coin finalBalance = (balance.multiply(btcRate)).divide(SATOSHI_IN_BTC);

						viewBalanceLocal.setVisibility(View.VISIBLE);
                        viewBalanceLocal.setFormat(Constants.LOCAL_FORMAT.code(0, Constants.PREFIX_ALMOST_EQUAL_TO + "BTC"));
                        viewBalanceLocal.setAmount(finalBalance);
						viewBalanceLocal.setTextColor(getResources().getColor(R.color.fg_less_significant));
					}
					else
					{
						viewBalanceLocal.setVisibility(View.INVISIBLE);
					}
				}
			}
			else
			{
				viewBalanceBOB.setVisibility(View.INVISIBLE);
			}

			viewProgress.setVisibility(View.GONE);
		}
		else
		{
			viewProgress.setVisibility(View.VISIBLE);
			viewBalance.setVisibility(View.INVISIBLE);
		}
	}

	private final LoaderCallbacks<BlockchainState> blockchainStateLoaderCallbacks = new LoaderManager.LoaderCallbacks<BlockchainState>()
	{
		@Override
		public Loader<BlockchainState> onCreateLoader(final int id, final Bundle args)
		{
			return new BlockchainStateLoader(activity);
		}

		@Override
		public void onLoadFinished(final Loader<BlockchainState> loader, final BlockchainState blockchainState)
		{
			WalletBalanceFragment.this.blockchainState = blockchainState;

			updateView();
		}

		@Override
		public void onLoaderReset(final Loader<BlockchainState> loader)
		{
		}
	};

	private final LoaderCallbacks<Coin> balanceLoaderCallbacks = new LoaderManager.LoaderCallbacks<Coin>()
	{
		@Override
		public Loader<Coin> onCreateLoader(final int id, final Bundle args)
		{
			return new WalletBalanceLoader(activity, wallet);
		}

		@Override
		public void onLoadFinished(final Loader<Coin> loader, final Coin balance)
		{
			WalletBalanceFragment.this.balance = balance;

			activity.invalidateOptionsMenu();
			updateView();
		}

		@Override
		public void onLoaderReset(final Loader<Coin> loader)
		{
		}
	};

	private final LoaderCallbacks<Cursor> rateLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>()
	{
		@Override
		public Loader<Cursor> onCreateLoader(final int id, final Bundle args)
		{
			return new ExchangeRateLoader(activity, config);
		}

		@Override
		public void onLoadFinished(final Loader<Cursor> loader, final Cursor data)
		{
			if (data != null && data.getCount() > 0)
			{
				data.moveToFirst();
				exchangeRate = ExchangeRatesProvider.getExchangeRate(data);
				updateView();
			}
		}

		@Override
		public void onLoaderReset(final Loader<Cursor> loader)
		{
		}
	};
}
