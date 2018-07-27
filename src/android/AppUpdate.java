package com.izouma.appupdate;

import android.Manifest;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.support.v4.content.FileProvider;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.liulishuo.filedownloader.BaseDownloadTask;
import com.liulishuo.filedownloader.FileDownloadListener;
import com.liulishuo.filedownloader.FileDownloader;

import org.apache.cordova.BuildHelper;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;

import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * This class echoes a string called from JavaScript.
 */
public class AppUpdate extends CordovaPlugin {
    private String checkUpdateUrl;
    private String applicationId;
    private AlertDialog updateDialog;
    private boolean checkAgainOnResume;
    private boolean cancelable;
    private String url;

    @Override
    protected void pluginInitialize() {
        super.pluginInitialize();
        this.applicationId = (String) BuildHelper.getBuildConfigValue(cordova.getActivity(), "APPLICATION_ID");
        boolean checkUpdateOnLoad = preferences.getBoolean("CheckUpdateOnLoad", false);
        checkUpdateUrl = preferences.getString("CheckUpdateUrl", null);
        if (checkUpdateOnLoad) {
            checkUpdate(null);
        }
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("checkUpdate")) {
            this.checkUpdate(callbackContext);
            return true;
        }
        return false;
    }

    private void checkUpdate(CallbackContext callbackContext) {
        if (TextUtils.isEmpty(checkUpdateUrl)) {
            if (callbackContext != null) {
                callbackContext.error("检查更新URL是否正确");
            }
            return;
        }
        CheckTask checkTask = new CheckTask(callbackContext);
        checkTask.execute();
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        if (checkAgainOnResume) {
            checkUpdate(null);
        }
    }

    @Override
    public void requestPermissions(int requestCode) {
        super.requestPermissions(requestCode);
        cordova.requestPermissions(this, requestCode, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE});
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        super.onRequestPermissionResult(requestCode, permissions, grantResults);
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            download(url, cancelable);
        } else {
            updateDialog.show();
            new AlertDialog.Builder(cordova.getActivity(), AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                    .setTitle("提示")
                    .setMessage("需要相机权限")
                    .setPositiveButton("打开设置", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                            Intent localIntent = new Intent();
                            localIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            localIntent.setAction("android.settings.APPLICATION_DETAILS_SETTINGS");
                            localIntent.setData(Uri.fromParts("package", cordova.getActivity().getPackageName(), null));
                            cordova.getActivity().startActivity(localIntent);
                        }
                    })
                    .create()
                    .show();
        }
    }

    class CheckTask extends AsyncTask<Void, Void, JSONObject> {
        private CallbackContext callbackContext;

        public CheckTask(CallbackContext callbackContext) {
            this.callbackContext = callbackContext;
        }

        @Override
        protected JSONObject doInBackground(Void... voids) {
            try {
                URL url = new URL(checkUpdateUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10 * 1000);
                int code = conn.getResponseCode();
                if (code == 200) {
                    InputStream inputStream = conn.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    Log.d("app-update", response.toString());
                    JSONObject jsonObject = new JSONObject(response.toString());
                    if (jsonObject.getBoolean("success")) {
                        JSONObject data = jsonObject.getJSONObject("data");
                        JSONObject androidForce = data.getJSONObject("androidForce");
                        if (versionLessThan(androidForce.getString("hintVersion"))) {
                            return androidForce;
                        }

                        JSONObject android = data.getJSONObject("android");
                        if (versionLessThan(android.getString("hintVersion"))) {
                            return android;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        protected void onPostExecute(final JSONObject jsonObject) {
            super.onPostExecute(jsonObject);
            if (jsonObject == null) {
                return;
            }
            try {
                final boolean cancelable = "N".equals(jsonObject.getString("force"));
                checkAgainOnResume = !cancelable;
                final String newVersion = jsonObject.getJSONObject("updateVersionInfo").getString("version");
                if (cancelable) {
                    SharedPreferences prefs = cordova.getActivity().getSharedPreferences("update", Context.MODE_PRIVATE);
                    String lastCheckVersion = prefs.getString("version", null);
                    String lastCheckTime = prefs.getString("time", null);
                    if (!TextUtils.isEmpty(lastCheckVersion) && !TextUtils.isEmpty(lastCheckTime)) {
                        if (lastCheckVersion.equals(newVersion) && System.currentTimeMillis() - Long.valueOf(lastCheckTime) < 1 * 24 * 60 * 60 * 1000) {
                            return;
                        }
                    }
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(cordova.getActivity(), AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                        .setTitle("新版本：" + newVersion)
                        .setMessage(jsonObject.getString("msg"))
                        .setCancelable(false)
                        .setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    AppUpdate.this.url = jsonObject.getJSONObject("updateVersionInfo").getString("url");
                                    AppUpdate.this.cancelable = cancelable;
                                    requestPermissions(1);
//                                    download(jsonObject.getJSONObject("updateVersionInfo").getString("url"), cancelable);
                                    dialog.dismiss();
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                if (cancelable) {
                    builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                            SharedPreferences prefs = cordova.getActivity().getSharedPreferences("update", Context.MODE_PRIVATE);
                            prefs.edit()
                                    .putString("time", System.currentTimeMillis() + "")
                                    .putString("version", newVersion)
                                    .apply();
                        }
                    });
                }
                updateDialog = builder.create();
                updateDialog.show();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void download(String url, boolean cancelable) {
        FileDownloader.setup(cordova.getActivity());
        final ProgressDialog progressDialog = new ProgressDialog(cordova.getActivity(), AlertDialog.THEME_DEVICE_DEFAULT_LIGHT);
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.setCancelable(false);
        progressDialog.setMessage("正在下载");
        progressDialog.setProgress(0);

        final File apkFile = new File(Environment.getExternalStoragePublicDirectory("apk"), System.currentTimeMillis() + ".apk");
        final BaseDownloadTask downloadTask = FileDownloader.getImpl().create(url)
                .setPath(apkFile.getPath())
                .setListener(new FileDownloadListener() {
                    @Override
                    protected void pending(BaseDownloadTask task, int soFarBytes, int totalBytes) {
                    }

                    @Override
                    protected void connected(BaseDownloadTask task, String etag, boolean isContinue, int soFarBytes, int totalBytes) {
                    }

                    @Override
                    protected void progress(BaseDownloadTask task, int soFarBytes, int totalBytes) {
                        progressDialog.setProgress((int) (soFarBytes * 100L / totalBytes));
                    }

                    @Override
                    protected void blockComplete(BaseDownloadTask task) {
                    }

                    @Override
                    protected void retry(final BaseDownloadTask task, final Throwable ex, final int retryingTimes, final int soFarBytes) {
                    }

                    @Override
                    protected void completed(BaseDownloadTask task) {
                        progressDialog.dismiss();
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        Uri apkUri = FileProvider.getUriForFile(cordova.getActivity(), applicationId + ".provider", apkFile);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                        cordova.getActivity().startActivity(intent);
                    }

                    @Override
                    protected void paused(BaseDownloadTask task, int soFarBytes, int totalBytes) {
                    }

                    @Override
                    protected void error(BaseDownloadTask task, Throwable e) {
                        Toast.makeText(cordova.getActivity(), "下载失败", Toast.LENGTH_SHORT).show();
                        progressDialog.dismiss();
                        updateDialog.show();
                    }

                    @Override
                    protected void warn(BaseDownloadTask task) {
                    }
                });
        if (cancelable) {
            progressDialog.setButton(DialogInterface.BUTTON_NEGATIVE, "取消", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    downloadTask.pause();
                }
            });
        }
        progressDialog.show();
        downloadTask.start();
    }

    private int getVersionValue(String version) {
        String[] arr = version.split("\\.");
        int value = 0;
        for (int i = 0; i < arr.length; i++) {
            value += Math.pow(10, i) * Integer.valueOf(arr[arr.length - 1 - i]);
        }
        return value;
    }

    private boolean versionLessThan(String version) {
        try {
            String versionName = cordova.getActivity().getPackageManager().getPackageInfo(cordova.getActivity().getPackageName(), 0).versionName;
            return getVersionValue(versionName) <= getVersionValue(version);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return false;
    }
}
