package com.chat.localemulti;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends Activity implements ChatReteMultiV8.ChatEventListener {

    private ChatReteMultiV8 rete;
    private TextView tvChat;
    private EditText etMsg, etIpDest, etNome, etCodice;
    private Button btnInizia, btnConnetti, btnInvia;
    private Handler handler;
    private Timer timerKeepAlive;
    private boolean chatAttiva = false;
    private int porta = 5050;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);

        etNome = new EditText(this); etNome.setHint("Nome"); etNome.setText("AndroidDevice");
        etCodice = new EditText(this); etCodice.setHint("Codice"); etCodice.setText("1234");
        btnInizia = new Button(this); btnInizia.setText("Attiva Chat");

        etIpDest = new EditText(this); etIpDest.setHint("IP PC o altro Android");
        btnConnetti = new Button(this); btnConnetti.setText("Aggiungi PC");

        tvChat = new TextView(this);
        tvChat.setMinLines(10);
        tvChat.setBackgroundColor(0xFFEEEEEE);

        etMsg = new EditText(this); etMsg.setHint("Scrivi messaggio...");
        btnInvia = new Button(this); btnInvia.setText("Invia");

        root.addView(etNome); root.addView(etCodice); root.addView(btnInizia);
        root.addView(etIpDest); root.addView(btnConnetti);
        root.addView(tvChat); root.addView(etMsg); root.addView(btnInvia);
        setContentView(root);

        handler = new Handler();
        rete = new ChatReteMultiV8(this);

        btnInizia.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!chatAttiva) {
                    rete.avviaServer(porta, etNome.getText().toString(), etCodice.getText().toString());
                    chatAttiva = true;
                    btnInizia.setText("Disconnetti");
                    avviaTimerRiconnessione();
                } else {
                    rete.chiudiTutto();
                    if (timerKeepAlive != null) timerKeepAlive.cancel();
                    chatAttiva = false;
                    btnInizia.setText("Attiva Chat");
                    appendLog("Disconnesso.");
                }
            }
        });

        btnConnetti.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String target = etIpDest.getText().toString().trim();
                if (!target.isEmpty() && chatAttiva) {
                    rete.connettiA(target, porta);
                    appendLog("Tentativo di connessione verso " + target + "...");
                }
            }
        });

        btnInvia.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = etMsg.getText().toString().trim();
                if (!t.isEmpty() && chatAttiva) {
                    rete.inviaTestoATutti(t);
                    appendLog("Tu: " + t);
                    etMsg.setText("");
                }
            }
        });
    }

    private void avviaTimerRiconnessione() {
        timerKeepAlive = new Timer();
        timerKeepAlive.schedule(new TimerTask() {
            @Override
            public void run() {
                if (chatAttiva) {
                    rete.inviaPingATutti();
                    final String target = etIpDest.getText().toString().trim();
                    if (!target.isEmpty()) {
                        rete.connettiA(target, porta);
                    }
                }
            }
        }, 6000, 6000);
    }

    private void appendLog(final String testo) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                tvChat.append(testo + "\n");
            }
        });
    }

    @Override public void onStato(String msg) { appendLog("[Stato] " + msg); }
    @Override public void onErrore(String err) { appendLog("[Errore] " + err); }
    @Override public void onConnesso(String nome, String ip) { appendLog("--> ENTRATO IN CHAT: " + nome + " (" + ip + ")"); }
    @Override public void onDisconnesso(String nome, String ip) { appendLog("<-- USCITO DALLA CHAT: " + nome); }
    @Override public void onTestoRicevuto(String nome, String testo) { appendLog(nome + ": " + testo); }
    @Override public void onPrivatoRicevuto(String nome, String testo) { appendLog("[Privato] " + nome + ": " + testo); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        rete.chiudiTutto();
        if (timerKeepAlive != null) timerKeepAlive.cancel();
    }
}
