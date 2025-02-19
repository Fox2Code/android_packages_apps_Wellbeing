package org.eu.droid_ng.wellbeing.prefs;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;

import org.eu.droid_ng.wellbeing.R;

public class MainActivity extends AppCompatActivity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.settings_activity);
		setSupportActionBar(findViewById(R.id.topbar));
		ActionBar actionBar = getSupportActionBar();
		assert actionBar != null;
		actionBar.setDisplayHomeAsUpEnabled(true);
		if (savedInstanceState == null) {
			getSupportFragmentManager()
					.beginTransaction()
					.replace(R.id.settings, new SettingsFragment())
					.commit();
		}
	}

	public static class SettingsFragment extends PreferenceFragmentCompat {
		@Override
		public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
			setPreferencesFromResource(R.xml.main_preferences, rootKey);
			Preference focusMode = findPreference("focus_mode");
			assert focusMode != null;
			focusMode.setOnPreferenceClickListener(p -> {
				startActivity(new Intent(getActivity(), FocusModeActivity.class));
				return true;
			});
			Preference sleepMode = findPreference("bedtime_mode");
			assert sleepMode != null;
			sleepMode.setOnPreferenceClickListener(p -> {
				startActivity(new Intent(getActivity(), BedtimeMode.class));
				return true;
			});
			Preference manual = findPreference("manual");
			assert manual != null;
			boolean show = requireActivity().getSharedPreferences("service", 0).getBoolean("manual", false);
			manual.setVisible(show);
			manual.setOnPreferenceClickListener(p -> {
				startActivity(new Intent(getActivity(), ManualSuspendActivity.class));
				return true;
			});
			Preference timers = findPreference("timers");
			assert timers != null;
			timers.setOnPreferenceClickListener(p -> {
				startActivity(new Intent(getActivity(), AppTimers.class));
				return true;
			});
			Preference settings = findPreference("settings");
			assert settings != null;
			settings.setOnPreferenceClickListener(p -> {
				startActivity(new Intent(getActivity(), SettingsActivity.class));
				return true;
			});
		}
	}
}