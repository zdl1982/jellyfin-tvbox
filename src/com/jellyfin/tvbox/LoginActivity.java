package com.jellyfin.tvbox;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class LoginActivity extends Activity {

    private EditText serverUrlEdit;
    private EditText usernameEdit;
    private EditText passwordEdit;
    private Button loginButton;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        serverUrlEdit = (EditText) findViewById(R.id.serverUrlEdit);
        usernameEdit = (EditText) findViewById(R.id.usernameEdit);
        passwordEdit = (EditText) findViewById(R.id.passwordEdit);
        loginButton = (Button) findViewById(R.id.loginButton);
        statusText = (TextView) findViewById(R.id.statusText);

        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doLogin();
            }
        });

        // Allow Enter key on TV remote to trigger login
        passwordEdit.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                    doLogin();
                    return true;
                }
                return false;
            }
        });

        // auto-focus on server URL
        serverUrlEdit.requestFocus();
    }

    private void doLogin() {
        final String server = serverUrlEdit.getText().toString().trim();
        final String user = usernameEdit.getText().toString().trim();
        final String pass = passwordEdit.getText().toString();

        if (server.isEmpty()) {
            statusText.setText("请输入服务器地址");
            return;
        }
        if (user.isEmpty()) {
            statusText.setText("请输入用户名");
            return;
        }

        statusText.setText("正在连接…");
        loginButton.setEnabled(false);

        new AsyncTask<Void, Void, String>() {
            private String token;
            @Override
            protected String doInBackground(Void... params) {
                try {
                    JellyfinClient client = new JellyfinClient(server);
                    client.login(user, pass);
                    token = client.getToken();
                    // save to app state
                    AppState.setClient(LoginActivity.this, client);
                    return null;
                } catch (Exception e) {
                    return e.getMessage();
                }
            }

            @Override
            protected void onPostExecute(String error) {
                loginButton.setEnabled(true);
                if (error == null) {
                    Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                    startActivity(intent);
                    finish();
                } else {
                    statusText.setText("登录失败: " + error);
                }
            }
        }.execute();
    }
}