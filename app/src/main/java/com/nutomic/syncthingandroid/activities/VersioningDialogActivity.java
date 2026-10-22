package com.nutomic.syncthingandroid.activities;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.nutomic.syncthingandroid.R;
import com.nutomic.syncthingandroid.fragments.dialog.ExternalVersioningFragment;
import com.nutomic.syncthingandroid.fragments.dialog.NoVersioningFragment;
import com.nutomic.syncthingandroid.fragments.dialog.SimpleVersioningFragment;
import com.nutomic.syncthingandroid.fragments.dialog.StaggeredVersioningFragment;
import com.nutomic.syncthingandroid.fragments.dialog.TrashCanVersioningFragment;

import java.util.Arrays;
import java.util.List;

public class VersioningDialogActivity extends ThemedAppCompatActivity {

    private Fragment mCurrentFragment;

    private static final List<String> mTypes = Arrays.asList("none", "trashcan", "simple", "staggered", "external");

    private static final String TAG = "VersioningDialogActivity";

    private Bundle mArguments;

    private OnBackPressedCallback mBackPressedCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            saveConfiguration();
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fragment_versioning_dialog);

        if (savedInstanceState != null) {
            mArguments = savedInstanceState.getBundle("arguments");
        } else {
            mArguments = getIntent().getExtras();
        }

        // The current type is read from the restored arguments: after a rotation, the intent
        // still holds the type the dialog was opened with.
        String type = mArguments.getString("type");
        int typeIndex = mTypes.indexOf(type);
        if (typeIndex < 0) {
            // A type this dialog cannot show, e.g. added by a newer Syncthing: showing it as
            // another type would overwrite it when the dialog closes.
            Log.w(TAG, "Unsupported versioning type: " + type);
            Toast.makeText(this, R.string.versioning_type_unsupported, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        updateFragmentView(typeIndex);
        initiateFinishBtn();
        initiateSpinner(typeIndex);

        // Register OnBackPressedCallback
        getOnBackPressedDispatcher().addCallback(this, mBackPressedCallback);
    }

    private void initiateFinishBtn() {
        Button finishBtn = findViewById(R.id.finish_btn);
        finishBtn.setOnClickListener(v -> {
            saveConfiguration();
            finish();
        });
    }

    private void saveConfiguration() {
        Intent intent = new Intent();
        intent.putExtras(mCurrentFragment.getArguments());
        setResult(AppCompatActivity.RESULT_OK, intent);
    }

    private void initiateSpinner(int typeIndex) {
        Spinner versioningTypeSpinner = findViewById(R.id.versioningTypeSpinner);
        versioningTypeSpinner.setSelection(typeIndex);
        versioningTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != mTypes.indexOf(mArguments.getString("type"))) {
                    updateVersioningType(position);
                    updateFragmentView(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
    }

    private void updateVersioningType(int position) {
        mArguments.putString("type", mTypes.get(position));
    }

    private void updateFragmentView(int selection) {
        if (mCurrentFragment != null){
            mArguments = mCurrentFragment.getArguments();
        }

        mCurrentFragment = getFragment(selection);
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();

        // This Activity (VersioningDialogActivity) contains all the file versioning parameters that have been passed from the FolderActivity in the intent extras, so we simply
        // pass that to the currentfragment.
        mCurrentFragment.setArguments(mArguments);
        transaction.replace(R.id.versioningFragmentContainer, mCurrentFragment);
        transaction.commit();
    }

    private Fragment getFragment(int selection) {
        Fragment fragment = null;

        switch (selection) {
            case 0:
                fragment = new NoVersioningFragment();
                break;
            case 1:
                fragment = new TrashCanVersioningFragment();
                break;
            case 2:
                fragment = new SimpleVersioningFragment();
                break;
            case 3:
                fragment = new StaggeredVersioningFragment();
                break;
            case 4:
                fragment = new ExternalVersioningFragment();
                break;
        }

        return fragment;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBundle("arguments", mCurrentFragment.getArguments());
    }
}
