# BlazeMeter Blazor Plugin for JMeter

Records and replays a **Blazor Server** circuit. The browser loads one HTML page, negotiates a SignalR connection, then keeps a WebSocket open and speaks the binary **blazorpack** protocol. JMeter's HTTP(S) recorder never stores that socket, so a normal HTTP script does not click, navigate, or hold the circuit.

This plugin is one jar. It does not use the WebSocket sampler, a Burp extension, or a separately copied BlazorPack jar.

## What you add in JMeter

| Element | Menu | What it does |
| --- | --- | --- |
| `bzm - Blazor Recorder` | Non-Test Elements | Local proxy. Browse the app, then it inserts samplers. |
| `bzm - Blazor Server` | Samplers | One circuit step: Connect, Navigate, Invoke, or Disconnect. |

A thread owns one circuit per connection name (default `blazor`). Connect opens it. Later samplers on that thread reuse it. The thread closes it when the thread ends, or when a Disconnect sampler runs.

## Build and install

Java 17 and JMeter 5.6 or newer.

```bash
mvn package
cp target/jmeter-bzm-blazor-0.1.0.jar "$JMETER_HOME/lib/ext"
```

Restart JMeter.

## Record

1. Add **bzm - Blazor Recorder** under the test plan and press **Start**. The default listen address is `127.0.0.1:8888`.
2. Set the browser HTTP proxy to `127.0.0.1` port `8888`. Include the application host; do not bypass LAN addresses.
3. Open the app over **http** and use it: follow links, click, type. Connect, Navigate, and Invoke samplers appear in the first thread group while you browse. A thread group named **Blazor Users** is created when the plan does not have one.
4. Press **Stop** to close the circuit and add Disconnect. Firefox's `permessage-deflate` extension is removed so the circuit messages stay readable; the page still loads either way.

Stop drops the circuit bookkeeping (handshake, `StartCircuit`, root-component batches, render acknowledgements, pings). What remains is the user-level script:

- **Connect** for the page that opened the circuit. On replay it loads that page again, negotiates, and attaches the root components from the live HTML.
- **Navigate** for each `OnLocationChanged`. On replay it tells the circuit, fetches the new document the way enhanced navigation does, and updates the root components.
- **Invoke** for other client calls, usually `BeginInvokeDotNetFromJS` for a click or input.
- **Disconnect** at the end of the circuit.

`examples/harbor.jmx` is a hand-written plan for the Harbor app at `http://192.168.1.101:5000/`: connect, open Market, open Studio, disconnect. Add a listener if you want the transcript in the GUI; the file already includes a View Results Tree.

## Sampler fields

- **Server URL** — page Connect should load, for example `http://192.168.1.101:5000/`.
- **Connection name** — circuit key on this thread.
- **Action** — Connect, Navigate, Invoke, Disconnect.
- **Location** — absolute URL or path for Navigate, such as `/market`.
- **Hub method** and **Arguments** — Invoke only. Arguments are a JSON array, for example `["", "", "DispatchEventAsync", 0, "[]"]`.
- **Timeout (ms)** — how long a step waits for the circuit.

The sample response is a transcript: page fetches, hub calls sent, and the server messages that came back (`JS.RenderBatch`, `JS.AttachComponent`, and so on). A `JS.Error` or a server close fails the sample.

## Checked against Harbor

`mvn test` talks to `http://192.168.1.101:5000/` when that host answers (skip with `-Dblazor.live=false`):

- Connect, then Navigate to `/market`, `/studio`, and `/desk`, with render batches and no circuit error.
- The same steps through the JMeter sampler.
- A proxy recording of that navigation, including the JDK WebSocket client (it uses `CONNECT`) and a browser-style `GET` upgrade.

## Limits

- **HTTPS recording.** A TLS `CONNECT` tunnel is forwarded and not decoded, so an `https://` Blazor app is not recorded. Replay itself does speak `https` and `wss`.
- **Browser return values.** Render batches are acknowledged. JavaScript calls are completed with a null result, which is enough for the Harbor pages. A call that needs a real DOM return value is not executed in a browser.
- **Clicks.** A recorded `BeginInvokeDotNetFromJS` is replayed with the same arguments. That works when the new circuit assigns the same event ids. Navigation does not depend on those ids: Navigate rebuilds the root components from the live page.
- **WebAssembly roots** are not attached. An `auto` root is attached as a server component.
- **WinSock.** Not part of this plugin. Blazor Server is HTTP plus a WebSocket. LoadRunner `lrs_*` scripts are already converted to JSR223 socket samplers by the Shift Left converter; `lrs_start_ssl` is still the gap in that converter, and it is a different protocol from Blazor.

## Layout

```
com.blazemeter.jmeter.blazor.BlazorSampler          sampler, GUI class is BlazorSamplerGui
com.blazemeter.jmeter.blazor.BlazorRecorder         recorder element, GUI class is BlazorRecorderGui
com.blazemeter.jmeter.blazor.protocol               JSON, MessagePack, blazorpack frames, HTML markers
com.blazemeter.jmeter.blazor.client.BlazorSession   one circuit; HTTP and WebSocket go through Jetty 12
com.blazemeter.jmeter.blazor.recorder               proxy and WebSocket frames
```

The jar embeds Jetty 12 for HTTP and WebSocket, so install is still one file in `lib/ext`. JMeter is `provided`. MessagePack and the SignalR length prefix are implemented here, matching the `blazorpack` protocol version the browser sends when stateful reconnect is off (`version: 1`).
