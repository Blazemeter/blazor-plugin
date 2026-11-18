package com.blazemeter.jmeter.blazor;

import com.blazemeter.jmeter.blazor.recorder.RecordedServer;
import com.blazemeter.jmeter.blazor.recorder.RecordedStep;
import com.blazemeter.jmeter.blazor.recorder.RecorderTemplate;
import com.blazemeter.jmeter.blazor.recorder.RecordingGroupWindow;
import com.blazemeter.jmeter.blazor.recorder.RecordingProxy;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.tree.TreePath;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.gui.ArgumentsPanel;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.gui.LogicControllerGui;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.AbstractJMeterGuiComponent;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.MenuFactory;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Start a local proxy and insert Connect / Navigate / Invoke samplers into
 * the first thread group as the browser uses the Blazor app. The server
 * origin is stored in a numbered User Defined Variable, {@code SERVER_1},
 * {@code SERVER_2}, and so on. A small
 * window stays above the other applications so the steps recorded since the
 * last group can be wrapped in a Simple Controller. Stop and the window's
 * close button both end the recording.
 */
public class BlazorRecorderGui extends AbstractJMeterGuiComponent {

    private static final long serialVersionUID = 1L;

    static {
        RecorderTemplate.install();
    }

    private static RecordingProxy running;

    private final JTextField port = new JTextField("8888", 6);
    private final JTextArea status = new JTextArea(12, 50);
    private final JButton start = new JButton("Start");
    private Timer refresher;
    private String serverUrl = "";
    private String serverOrigin = "";
    private String serverVariable = "";
    private transient JMeterTreeNode variablesNode;
    private transient String certificateHint = "";
    private transient RecordingGroupWindow groupWindow;
    private transient List<JMeterTreeNode> recordedNodes = new ArrayList<>();

    public BlazorRecorderGui() {
        super();
        init();
    }

    private void init() {
        setLayout(new BorderLayout(0, 8));
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(new JLabel("Listen port"));
        controls.add(port);
        controls.add(start);
        add(controls, BorderLayout.CENTER);

        status.setEditable(false);
        status.setText("Point the browser HTTP and SOCKS proxy at 127.0.0.1 (or localhost) and this port.\n"
                + "Start recording, then trust the certificate path shown here. HTTPS is recorded, including sites that use HTTP/2.\n"
                + "Each server is stored as ${SERVER_1}, ${SERVER_2}, and so on. A window stays on top so you can group steps.\n"
                + "Stop, or close that window, to finish recording.");
        add(new JScrollPane(status), BorderLayout.SOUTH);

        start.addActionListener(event -> startRecording());
    }

    private void startRecording() {
        if (running != null) {
            status.setText("Already recording on port " + running.port());
            return;
        }
        int listen;
        try {
            listen = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException ex) {
            JMeterUtils.reportErrorToUser("Port must be a number", "Blazor Recorder");
            return;
        }
        try {
            ensureVariables();
        } catch (Exception ex) {
            JMeterUtils.reportErrorToUser(ex.getMessage(), "Blazor Recorder");
            return;
        }
        serverUrl = "";
        serverOrigin = "";
        serverVariable = "";
        if (recordedNodes == null) {
            recordedNodes = new ArrayList<>();
        }
        recordedNodes.clear();
        if (groupWindow != null) {
            groupWindow.dispose();
        }
        groupWindow = new RecordingGroupWindow(this, this::groupSamplers, this::stopRecording);
        RecordingProxy proxy = new RecordingProxy();
        proxy.setStepListener(step -> {
            if (SwingUtilities.isEventDispatchThread()) {
                acceptStep(step);
            } else {
                SwingUtilities.invokeLater(() -> acceptStep(step));
            }
        });
        try {
            proxy.start(listen);
        } catch (Exception ex) {
            groupWindow.dispose();
            groupWindow = null;
            JMeterUtils.reportErrorToUser(ex.getMessage(), "Blazor Recorder");
            return;
        }
        running = proxy;
        try {
            certificateHint = "Trust this certificate in the browser to record HTTPS:\n"
                    + proxy.ensureCertificate() + "\n";
        } catch (Exception ex) {
            certificateHint = "HTTPS certificate was not created: " + ex.getMessage() + "\n";
        }
        start.setEnabled(false);
        groupWindow.show();
        refresher = new Timer(400, event -> refreshStatus());
        refresher.start();
        refreshStatus();
    }

    private void acceptStep(RecordedStep step) {
        try {
            if (step.kind() == RecordedStep.Kind.CONNECT) {
                String origin = RecordedServer.origin(step.value());
                if (!origin.isEmpty()) {
                    storeServer(origin);
                }
            }
            insert(List.of(step));
        } catch (Exception ex) {
            status.append("\nCould not insert " + step.label() + ": " + ex.getMessage());
            JMeterUtils.reportErrorToUser(ex.getMessage(), "Blazor Recorder");
        }
        touchGroupWindow();
    }

    private void refreshStatus() {
        RecordingProxy proxy = running;
        if (proxy == null) {
            return;
        }
        StringBuilder report = new StringBuilder();
        report.append("Recording on 127.0.0.1:").append(proxy.port()).append('\n');
        report.append(certificateHint);
        if (serverOrigin.isEmpty()) {
            report.append("The server variable is empty until the app opens a circuit.\n");
        } else {
            report.append("${").append(serverVariable).append("} = ").append(serverOrigin).append('\n');
        }
        report.append("Samplers are added to the first thread group. ")
                .append("Group them from the window, or close it to stop.\n");
        List<String> seen = proxy.seenRequests();
        report.append("Browser requests seen: ").append(seen.size()).append('\n');
        int from = Math.max(0, seen.size() - 8);
        for (int i = from; i < seen.size(); i++) {
            String line = seen.get(i);
            if (line.startsWith("CONNECT ") && line.contains(":443")) {
                continue;
            }
            report.append(line).append('\n');
        }
        for (RecordedStep step : proxy.steps()) {
            report.append("• ").append(step.label()).append('\n');
        }
        status.setText(report.toString());
        touchGroupWindow();
    }

    private void stopRecording() {
        RecordingProxy proxy = running;
        RecordingGroupWindow window = groupWindow;
        if (proxy == null && window == null) {
            return;
        }
        if (refresher != null) {
            refresher.stop();
            refresher = null;
        }
        running = null;
        groupWindow = null;
        start.setEnabled(true);
        if (window != null) {
            SwingUtilities.invokeLater(window::dispose);
        }
        if (proxy == null) {
            return;
        }
        proxy.stop();
        proxy.setStepListener(null);
        List<RecordedStep> steps = proxy.steps();
        StringBuilder report = new StringBuilder();
        report.append("Recorded ").append(steps.size()).append(" steps.\n");
        if (!serverOrigin.isEmpty()) {
            report.append("${").append(serverVariable).append("} = ").append(serverOrigin).append('\n');
        }
        for (RecordedStep step : steps) {
            report.append(step.label());
            if (!step.arguments().isEmpty()) {
                report.append(' ').append(step.arguments());
            }
            report.append('\n');
        }
        report.append("Samplers are in the first thread group.");
        status.setText(report.toString());
    }

    private void touchGroupWindow() {
        if (groupWindow == null) {
            return;
        }
        groupWindow.setServer(serverVariable, serverOrigin);
        groupWindow.setUngrouped(ungroupedSamplers().size());
    }

    private void groupSamplers() {
        if (groupWindow == null) {
            return;
        }
        List<JMeterTreeNode> pending = ungroupedSamplers();
        if (pending.isEmpty()) {
            groupWindow.setMessage("No samplers to group yet.");
            return;
        }
        String controllerName = groupWindow.groupName();
        String controllerComment = groupWindow.groupComment();
        try {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui == null) {
                groupWindow.setMessage("JMeter GUI is not available.");
                return;
            }
            JMeterTreeNode parent = (JMeterTreeNode) pending.get(0).getParent();
            JMeterTreeModel model = gui.getTreeModel();
            int index = parent.getIndex(pending.get(0));
            GenericController controller = new GenericController();
            controller.setName(controllerName);
            controller.setComment(controllerComment);
            controller.setProperty(TestElement.GUI_CLASS, LogicControllerGui.class.getName());
            controller.setProperty(TestElement.TEST_CLASS, GenericController.class.getName());
            JMeterTreeNode controllerNode = model.addComponent(controller, parent);
            model.removeNodeFromParent(controllerNode);
            for (int i = pending.size() - 1; i >= 0; i--) {
                model.removeNodeFromParent(pending.get(i));
            }
            model.insertNodeInto(controllerNode, parent, Math.min(index, parent.getChildCount()));
            for (JMeterTreeNode sampler : pending) {
                model.insertNodeInto(sampler, controllerNode, controllerNode.getChildCount());
            }
            reveal(controllerNode);
            gui.setDirty(true);
            groupWindow.setMessage("Grouped " + pending.size() + " samplers into \"" + controllerName + "\".");
            touchGroupWindow();
        } catch (Exception ex) {
            groupWindow.setMessage(ex.getMessage() == null ? "Could not group samplers." : ex.getMessage());
            JMeterUtils.reportErrorToUser(ex.getMessage(), "Blazor Recorder");
        }
    }

    private List<JMeterTreeNode> ungroupedSamplers() {
        if (recordedNodes == null) {
            recordedNodes = new ArrayList<>();
        }
        recordedNodes.removeIf(node -> node.getParent() == null);
        JMeterTreeNode parent = null;
        List<JMeterTreeNode> pending = new ArrayList<>();
        for (JMeterTreeNode node : recordedNodes) {
            if (!(node.getParent() instanceof JMeterTreeNode candidate)) {
                continue;
            }
            if (!(candidate.getTestElement() instanceof AbstractThreadGroup)) {
                continue;
            }
            if (!(node.getTestElement() instanceof BlazorSampler)) {
                continue;
            }
            if (parent == null) {
                parent = candidate;
            }
            if (candidate == parent) {
                pending.add(node);
            }
        }
        return pending;
    }

    private static void reveal(JMeterTreeNode node) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || gui.getMainFrame() == null) {
            return;
        }
        JTree tree = gui.getMainFrame().getTree();
        tree.expandPath(new TreePath(node.getPath()));
    }

    private void ensureVariables() throws IllegalUserActionException {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) {
            throw new IllegalStateException("JMeter GUI is not available");
        }
        JMeterTreeModel model = gui.getTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        JMeterTreeNode plan = findTestPlan(root);
        if (plan == null) {
            throw new IllegalStateException("Could not find the test plan");
        }
        if (variablesNode != null && inTree(variablesNode, plan)
                && variablesNode.getTestElement() instanceof Arguments) {
            return;
        }
        variablesNode = findServerVariables(plan);
        if (variablesNode != null) {
            return;
        }
        Arguments arguments = new Arguments();
        arguments.setName("User Defined Variables");
        arguments.setComment("Blazor recorder. Each origin is ${SERVER_1}, ${SERVER_2}, and so on.");
        arguments.setProperty(TestElement.GUI_CLASS, ArgumentsPanel.class.getName());
        arguments.setProperty(TestElement.TEST_CLASS, Arguments.class.getName());
        variablesNode = model.addComponent(arguments, plan);
        if (plan.getIndex(variablesNode) != 0) {
            model.removeNodeFromParent(variablesNode);
            model.insertNodeInto(variablesNode, plan, 0);
        }
        reveal(plan);
        gui.setDirty(true);
    }

    /** True when {@code node} is {@code plan} or still sits under it. A node left over from a closed plan does not count. */
    private static boolean inTree(JMeterTreeNode node, JMeterTreeNode plan) {
        for (javax.swing.tree.TreeNode cursor = node; cursor != null; cursor = cursor.getParent()) {
            if (cursor == plan) {
                return true;
            }
        }
        return false;
    }

    private void storeServer(String origin) throws IllegalUserActionException {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) {
            throw new IllegalStateException("JMeter GUI is not available");
        }
        // Save the open screen first. Otherwise a later save of that screen can
        // replace the argument we are about to add with the table's old rows.
        gui.updateCurrentNode();
        ensureVariables();
        if (!(variablesNode.getTestElement() instanceof Arguments arguments)) {
            throw new IllegalStateException("User Defined Variables element is missing");
        }
        String variable = RecordedServer.variableFor(serverVariables(arguments), origin);
        setArgument(arguments, variable, origin);
        serverVariable = variable;
        serverOrigin = origin;
        gui.setDirty(true);
        if (gui.getCurrentNode() == variablesNode) {
            gui.refreshCurrentGui();
        }
        reveal(variablesNode);
    }

    private static Map<String, String> serverVariables(Arguments arguments) {
        Map<String, String> defined = new LinkedHashMap<>();
        CollectionProperty items = arguments.getArguments();
        if (items == null) {
            return defined;
        }
        for (int i = 0; i < items.size(); i++) {
            JMeterProperty property = items.get(i);
            if (!(property.getObjectValue() instanceof Argument argument)) {
                continue;
            }
            if (RecordedServer.VARIABLE.equals(argument.getName()) || RecordedServer.index(argument.getName()) > 0) {
                defined.put(argument.getName(), argument.getValue());
            }
        }
        return defined;
    }

    private static void setArgument(Arguments arguments, String name, String value) {
        CollectionProperty items = arguments.getArguments();
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                JMeterProperty property = items.get(i);
                if (property.getObjectValue() instanceof Argument argument && name.equals(argument.getName())) {
                    argument.setValue(value);
                    return;
                }
            }
        }
        arguments.addArgument(name, value, "=");
    }

    private static JMeterTreeNode findServerVariables(JMeterTreeNode node) {
        if (node.getTestElement() instanceof Arguments arguments && arguments.getClass() == Arguments.class
                && arguments.getArguments() != null
                && "User Defined Variables".equals(node.getName())) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            JMeterTreeNode found = findServerVariables((JMeterTreeNode) node.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The model root is hidden and shares its test element with the visible
     * test plan, so the plan to attach elements to is the visible child.
     */
    private static JMeterTreeNode findTestPlan(JMeterTreeNode root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) root.getChildAt(i);
            if (child.getTestElement() instanceof TestPlan) {
                return child;
            }
        }
        return null;
    }

    private int insert(List<RecordedStep> steps) throws IllegalUserActionException {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) {
            throw new IllegalStateException("JMeter GUI is not available");
        }
        JMeterTreeModel model = gui.getTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        JMeterTreeNode threadGroup = findThreadGroup(root);
        if (threadGroup == null) {
            JMeterTreeNode plan = findTestPlan(root);
            if (plan == null) {
                throw new IllegalStateException("Could not find the test plan");
            }
            ThreadGroup group = new ThreadGroup();
            group.setName("Blazor Users");
            group.setNumThreads(1);
            group.setRampUp(1);
            LoopController loop = new LoopController();
            loop.setLoops(1);
            loop.setContinueForever(false);
            loop.initialize();
            group.setSamplerController(loop);
            model.addComponent(group, plan);
            threadGroup = findNode(plan, group);
        }
        if (threadGroup == null) {
            throw new IllegalStateException("Could not find a thread group to hold the recording");
        }
        int count = 0;
        for (RecordedStep step : steps) {
            BlazorSampler sampler = new BlazorSampler();
            sampler.setName(step.label());
            sampler.setProperty(TestElement.GUI_CLASS, BlazorSamplerGui.class.getName());
            sampler.setProperty(TestElement.TEST_CLASS, BlazorSampler.class.getName());
            sampler.setConnection("blazor");
            sampler.setTimeout("20000");
            if (step.kind() == RecordedStep.Kind.CONNECT) {
                serverUrl = step.value();
                sampler.setAction(BlazorSampler.CONNECT);
                sampler.setServerUrl(serverField(serverUrl));
            } else if (step.kind() == RecordedStep.Kind.NAVIGATE) {
                sampler.setAction(BlazorSampler.NAVIGATE);
                sampler.setServerUrl(serverField(serverUrl));
                sampler.setLocation(RecordedServer.reference(step.value(), serverOrigin, serverVariable));
            } else if (step.kind() == RecordedStep.Kind.INVOKE) {
                sampler.setAction(BlazorSampler.INVOKE);
                sampler.setServerUrl(serverField(serverUrl));
                sampler.setTarget(step.value());
                sampler.setArguments(step.arguments());
            } else {
                sampler.setAction(BlazorSampler.DISCONNECT);
                sampler.setServerUrl(serverField(serverUrl));
            }
            if (recordedNodes == null) {
                recordedNodes = new ArrayList<>();
            }
            recordedNodes.add(model.addComponent(sampler, threadGroup));
            count++;
        }
        gui.setDirty(true);
        return count;
    }

    private String serverField(String url) {
        if (serverOrigin.isEmpty() || serverVariable.isEmpty()) {
            return url == null ? "" : url;
        }
        return RecordedServer.reference(url, serverOrigin, serverVariable);
    }

    private static JMeterTreeNode findThreadGroup(JMeterTreeNode node) {
        if (node.getTestElement() instanceof AbstractThreadGroup) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            JMeterTreeNode found = findThreadGroup((JMeterTreeNode) node.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static JMeterTreeNode findNode(JMeterTreeNode node, TestElement element) {
        if (node.getTestElement() == element) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            JMeterTreeNode found = findNode((JMeterTreeNode) node.getChildAt(i), element);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Override
    public String getLabelResource() {
        return "bzm_blazor_recorder";
    }

    @Override
    public String getStaticLabel() {
        return "bzm - Blazor Recorder";
    }

    @Override
    public Collection<String> getMenuCategories() {
        return Collections.singletonList(MenuFactory.NON_TEST_ELEMENTS);
    }

    @Override
    public JPopupMenu createPopupMenu() {
        return MenuFactory.getDefaultMenu();
    }

    @Override
    public TestElement createTestElement() {
        BlazorRecorder recorder = new BlazorRecorder();
        modifyTestElement(recorder);
        return recorder;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        super.configureTestElement(element);
        if (element instanceof BlazorRecorder recorder) {
            recorder.setPort(port.getText().trim());
        }
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        if (element instanceof BlazorRecorder recorder) {
            port.setText(recorder.getPort());
        }
    }

    @Override
    public void clearGui() {
        super.clearGui();
        port.setText("8888");
    }
}
