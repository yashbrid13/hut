package server;

import server.controller.*;
import server.model.Agent;
import server.model.Coordinate;
import server.model.Sensor;
import server.model.State;
import server.model.target.AdjustableTarget;
import server.model.target.Target;
import server.model.task.Task;
import tool.GsonUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.logging.*;

/**
 * @author Feng Wu
 */
/* Edited by Yuai */
public class Simulator {

    private String webRef ="web";

    private final static String SERVER_CONFIG_FILE = "/config/serverConfig.json";
    private final static String SCENARIO_DIR_PATH = "/scenarios/";
    private Logger LOGGER;


    private State state;
    private Sensor sensor;

    private final QueueManager queueManager;
    private final AgentController agentController;
    private final TaskController taskController;
    private final TargetController targetController;
    private final ConnectionController connectionController;
    private final HazardController hazardController;
    private final Allocator allocator;

    private final ImageController imageController;

    public static Simulator instance;

    private static final double gameSpeed = 6;

    private Thread mainLoopThread;

    private final int port;

    public Simulator(int port) {
        instance = this;
        this.port = port;
        try {
            LogManager.getLogManager().readConfiguration(new FileInputStream("./loggingForStudy.properties"));
        } catch (final IOException e) {
            Logger.getAnonymousLogger().severe("Could not load default loggingForStudy.properties file");
            Logger.getAnonymousLogger().severe(e.getMessage());
        }
        this.LOGGER = Logger.getLogger(Simulator.class.getName() + port);
        state = new State(LOGGER);
        sensor = new Sensor(this, LOGGER);
        connectionController = new ConnectionController(this, LOGGER);
        allocator = new Allocator(this, LOGGER);
        queueManager = new QueueManager(this, LOGGER);
        agentController = new AgentController(this, sensor, LOGGER);
        taskController = new TaskController(this, LOGGER);
        hazardController = new HazardController(this, LOGGER);
        targetController = new TargetController(this, LOGGER);

        imageController = new ImageController(this, LOGGER);

        queueManager.initDroneDataConsumer();
    }

    public static void main(String[] args) {
        try {
            LogManager.getLogManager().readConfiguration(new FileInputStream("./loggingForStudy.properties"));
        } catch (final IOException e) {
            Logger.getAnonymousLogger().severe("Could not load default loggingForStudy.properties file");
            Logger.getAnonymousLogger().severe(e.getMessage());
        }

        //Setup GSON
        GsonUtils.registerTypeAdapter(Task.class, Task.taskSerializer);
        GsonUtils.registerTypeAdapter(State.HazardHitCollection.class, State.hazardHitsSerializer);
        GsonUtils.create();

        int port;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        } else {
            port = 44101;
        }
        new Simulator(port).start();
    }

    public void start() {
        /*
        try {
            LogManager.getLogManager().readConfiguration(new FileInputStream("./loggingForStudy.properties"));
        } catch (final IOException e) {
            Logger.getAnonymousLogger().severe("Could not load default loggingForStudy.properties file");
            Logger.getAnonymousLogger().severe(e.getMessage());
        }

         */

        //Setup GSON
        GsonUtils.registerTypeAdapter(Task.class, Task.taskSerializer);
        GsonUtils.registerTypeAdapter(State.HazardHitCollection.class, State.hazardHitsSerializer);
        GsonUtils.create();

        pushConfig(this.port);
        new Thread(connectionController::start).start();
        // LOGGER.info("Server ready.");
    }

    public void startSandboxMode() {
        this.state.setGameType(State.GAME_TYPE_SANDBOX);
        this.state.setGameId("Sandbox");
        LOGGER.info(String.format("%s; SBXLD; Sandbox loaded ", getState().getTime()));
        this.startSimulation();
    }

    public boolean loadScenarioMode(String scenarioFileName) {
        this.state.setGameType(State.GAME_TYPE_SCENARIO);
        if(loadScenarioFromFile(webRef+"/scenarios/" + scenarioFileName)) {
            //LOGGER.info(String.format("%s; SCLD; Scenario loaded (filename); %s ", getState().getTime(), scenarioFileName));
            return true;
        } else {
            //LOGGER.info(String.format("%s; SCUN; Unable to start scenario (filename); %s ", getState().getTime(), scenarioFileName));
            return false;
        }
    }

    public void startSimulation() {
        state.setScenarioStartTime();
        state.setScenarioEndTime();
        //Heart beat all virtual agents to prevent time out when user is reading the description.
        for(Agent agent : this.state.getAgents())
            if(agent.isSimulated())
                agent.heartbeat();
        this.agentController.stopAllAgents();
        this.mainLoopThread = new Thread(this::mainLoop);
        mainLoopThread.start();
        this.state.setInProgress(true);
        // LOGGER.info(String.format("%s; SIMST; Simulation started", getState().getTime()));
    }

    public Map<String, String> getScenarioFileListWithGameIds() {
        Map<String, String> scenarios = new HashMap<>();
        File scenarioDir = new File(webRef+SCENARIO_DIR_PATH);
        if(scenarioDir.exists() && scenarioDir.isDirectory()) {
            for(File file : scenarioDir.listFiles()) {
                if (!file.isDirectory()) {
                    String scenarioName = getScenarioNameFromFile(webRef + SCENARIO_DIR_PATH + file.getName());
                    if (scenarioName != null)
                        scenarios.put(file.getName(), scenarioName);
                }
            }
        }
        else
            LOGGER.info(String.format("%s; SCNF; Could not find scenario (directory); %s ", getState().getTime(), SCENARIO_DIR_PATH));
        return scenarios;
    }

    private void mainLoop() {
        final double waitTime = (int) (1000/(gameSpeed * 5)); //When gameSpeed is 1, should be 200ms.
        int sleepTime;
        do {
            long startTime = System.currentTimeMillis();
            state.incrementTime(0.2);
            if (state.getScenarioEndTime() !=0 && System.currentTimeMillis() >= state.getScenarioEndTime()) {
                if (state.isPassthrough()) {
                    updateNextValues();
                }
                this.reset();
            }

            //Check for wind change
            List<HashMap> futureWind = this.state.getFutureWind();
            Iterator<HashMap> i = futureWind.iterator();
            while (i.hasNext()) {
                HashMap<String, Double> wind = i.next();
                if (System.currentTimeMillis() >= state.getScenarioStartTime() + (long) (wind.get("time") * 1000)) {
                    state.setWindSpeed(wind.get("speed"));
                    state.setWindHeading(wind.get("heading"));
                    i.remove();
                    LOGGER.info(String.format("%s; WNDCHG; Wind speed and heading has changed (speed, heading); %s, %s ", getState().getTime(), wind.get("speed"), wind.get("heading")));
                }
            }

            //Step agents
            // checkAgentsForTimeout();
            for (Agent agent : state.getAgents()) {
                if (!agent.isTimedOut()) {
                    agent.step(state.isFlockingEnabled(), state.getAvgAgentDropout());
                    if (agent.isTimedOut()) {
                        LOGGER.info(String.format("%s; LSTCN; Lost connection with agent (id); %s ", getState().getTime(), agent.getId()));
                    }
                }
            }

            //Step tasks - requires completed tasks array to avoid concurrent modification.
            List<Task> completedTasks = new ArrayList<Task>();
            for (Task task : state.getTasks())
                if(task.step())
                    completedTasks.add(task);
            for(Task task : completedTasks) {
                task.complete();
                Simulator.instance.getAllocator().dynamicReassign();
                //printDiag();
            }

            //Step hazard hits
            // Disable this for persistent hazards and exploration heatmaps
            // this.state.decayHazardHits();

            // Check and trigger images that are scheduled
            imageController.checkForImages();

            long endTime = System.currentTimeMillis();
            sleepTime = (int) (waitTime - (endTime - startTime));
            if (sleepTime < 0) {
                sleepTime = 0;
            }
        } while (sleep(sleepTime));
    }

    private void updateNextValues() {
        try {
            String json = GsonUtils.readFile("web/scenarios/" + state.getNextFileName());
            Object obj = GsonUtils.fromJson(json);
            state.setGameId(GsonUtils.getValue(obj, "gameId"));
            state.setGameDescription(GsonUtils.getValue(obj, "gameDescription"));
            LOGGER.info(String.format("%s; SCPS; Passing through to next scenario ", getState().getTime()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void printDiag() {
        Map<String, String> decisions = imageController.getDecisions();
        decisions.entrySet().forEach(System.out::println);

    }

    /**
     * Check if any agents have timed out or reconnected this step.
     */
    private void checkAgentsForTimeout() {
        for (Agent agent : state.getAgents()) {
            //Check if agent is timed out
            if (agent.getMillisSinceLastHeartbeat() > 20 * 1000) {
                if(!agent.isTimedOut()) {
                    agent.setTimedOut(true);
                    LOGGER.info(String.format("%s; LSTCN; Lost connection with agent (id); %s ", getState().getTime(), agent.getId()));
                }
            }
        }
    }

    public void changeView(int modeFlag) {
        if (modeFlag == 2) {
            //agentController.stopAllAgents();
            //agentController.updateAgentsTempRoutes();
            //allocator.copyRealAllocToTempAlloc();
            //allocator.clearAllocationHistory();
            state.setEditMode(2);
        } else if (modeFlag == 1){
            //allocator.confirmAllocation(state.getAllocation());
            state.setEditMode(1);
        } else if (modeFlag == 3) {
            state.setEditMode(3);
            //Clear agents and tasks
            for(Agent agent : state.getAgents()) {
                agent.resume();
            }
        }
    }

    public void setProvDoc(String docid) {
        state.setProvDoc(docid);
    }

    public synchronized void reset() {
        if (this.mainLoopThread != null) {
            this.mainLoopThread.interrupt();
        }
        state.reset();
        agentController.resetAgentNumbers();
        hazardController.resetHazardNumbers();
        targetController.resetTargetNumbers();
        taskController.resetTaskNumbers();
        imageController.reset();

        LOGGER.info(String.format("%s; SVRST; Server reset ", getState().getTime()));
        /*
        LogManager.getLogManager().reset();
        try {
            LogManager.getLogManager().readConfiguration(new FileInputStream("./loggingForStudy.properties"));
        } catch (final IOException e) {
            Logger.getAnonymousLogger().severe("Could not load default loggingForStudy.properties file");
            Logger.getAnonymousLogger().severe(e.getMessage());
        }
        LOGGER = null;
        LOGGER = Logger.getLogger(Simulator.class.getName());

         */
    }

    public void resetLogging(List<String> userNames) {
        try {
            String fileName = String.join("-", userNames) + "-" + state.getGameId() + ".log";
            FileHandler fileHandler = new FileHandler(fileName);
            Handler[] currentHandlers = LOGGER.getHandlers();
            if (currentHandlers.length != 0) {
                FileHandler oldHandler = (FileHandler) currentHandlers[currentHandlers.length - 1];
                LOGGER.removeHandler(oldHandler);
            }
            LOGGER.addHandler(fileHandler);
            LOGGER.info(String.format("%s; LGSTRT; Reset log (scenario, usernames); %s; %s ", getState().getTime(), state.getGameId(), String.join(", ", userNames)));

        } catch (final IOException e) {
            Logger.getAnonymousLogger().severe("Could not load default loggingForStudy.properties file");
            Logger.getAnonymousLogger().severe(e.getMessage());
        }

        //System.out.println("Save now as " + userName + ", " + state.getGameId());
    }

    private void readConfig() {
        try {
            LOGGER.info(String.format("%s; RDCFG; Reading Server Config File (directory); %s ", getState().getTime(), webRef+SERVER_CONFIG_FILE));
            String json = GsonUtils.readFile(webRef+SERVER_CONFIG_FILE);
            Object obj = GsonUtils.fromJson(json);
            Double port = GsonUtils.getValue(obj, "port");

            connectionController.init((port != null) ? port.intValue() : 8080, webRef);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void pushConfig(int port) {
        connectionController.init(port, webRef);
    }

    private boolean loadScenarioFromFile(String scenarioFile) {
        try {
            String json = GsonUtils.readFile(scenarioFile);
            Object obj = GsonUtils.fromJson(json);

            this.state.setGameId(GsonUtils.getValue(obj, "gameId"));
            this.state.setGameDescription(GsonUtils.getValue(obj, "gameDescription"));
            Object centre = GsonUtils.getValue(obj, "gameCentre");
            this.state.setGameCentre(new Coordinate(GsonUtils.getValue(centre, "lat"), GsonUtils.getValue(centre, "lng")));

            if(GsonUtils.hasKey(obj,"allocationMethod")) {
                String allocationMethod = GsonUtils.getValue(obj, "allocationMethod")
                        .toString()
                        .toLowerCase();

                List<String> possibleMethods = new ArrayList<String>(Arrays.asList(
                        "random",
                        "maxsum"
                ));

                if(possibleMethods.contains(allocationMethod)) {
                    this.state.setAllocationMethod(allocationMethod);
                } else {
                    LOGGER.warning("Allocation method: '" + allocationMethod + "' not valid. Set to 'maxsum'.");
                    //state.allocationMethod initialised with default value of 'maxsum'
                }
            }

            if(GsonUtils.hasKey(obj,"flockingEnabled")){
                Object flockingEnabled = GsonUtils.getValue(obj, "flockingEnabled");
                if(flockingEnabled.getClass() == Boolean.class) {
                    this.state.setFlockingEnabled((Boolean)flockingEnabled);
                } else {
                    LOGGER.warning("Expected boolean value for flockingEnabled in scenario file. Received: '" +
                            flockingEnabled.toString() + "'. Set to false.");
                    // state.flockingEnabled initialised with default value of false
                }
            }

            Object hub = GsonUtils.getValue(obj, "hub");
            if(hub != null) {
                Double lat = GsonUtils.getValue(hub, "lat");
                Double lng = GsonUtils.getValue(hub, "lng");
                agentController.addHubAgent(lat, lng);
                state.setHubLocation(new Coordinate(lat, lng));
            }

            Boolean faultySwarm = GsonUtils.getValue(obj, "faultySwarm");
            Object faultySwarmObj = obj;
            if (faultySwarm != null && faultySwarm) {
                String faultySwarmJson = GsonUtils.readFile(webRef + "/scenarios/snippets/faultySwarm.json");
                faultySwarmObj = GsonUtils.fromJson(faultySwarmJson);
            }

            Object avgAgentDropout = GsonUtils.getValue(faultySwarmObj, "avgAgentDropout");
            if (avgAgentDropout != null) {
                if (avgAgentDropout.getClass() == Double.class ) {
                    this.state.setAvgAgentDropout((Double)avgAgentDropout);
                } else {
                    LOGGER.warning("Expected double value for avgAgentDropout in scenario file. Received: '" +
                            avgAgentDropout.toString() + "'. Set to 0.");
                    // state.avgAgentDropout initialised with default value of 0
                }
            }

            Object ignoredTaskProb = GsonUtils.getValue(faultySwarmObj, "ignoredTaskProb");
            if (ignoredTaskProb != null) {
                if (ignoredTaskProb.getClass() == Double.class ) {
                    this.state.setIgnoredTaskProb((Double)ignoredTaskProb);
                } else {
                    LOGGER.warning("Expected double value for ignoredTaskProb in scenario file. Received: '" +
                            ignoredTaskProb.toString() + "'. Set to 0.");
                    // state.ignoredTaskProb initialised with default value of 0
                }
            }

            Object chatEnabled = GsonUtils.getValue(obj, "chatEnabled");
            if (chatEnabled != null) {
                if (chatEnabled.getClass() == Boolean.class ) {
                    this.state.setChatEnabled((Boolean)chatEnabled);
                } else {
                    LOGGER.warning("Expected boolean value for chatEnabled in scenario file. Received: '" +
                            chatEnabled.toString() + "'. Set to false.");
                    // state.chatEnabled initialised with default value of false
                }
            }

            Object scenarioNumber = GsonUtils.getValue(obj, "scenarioNumber");
            if (scenarioNumber != null) {
                if (scenarioNumber.getClass() == Double.class ) {
                    this.state.setScenarioNumber((int) Math.round((Double)scenarioNumber));
                } else {
                    LOGGER.warning("Expected integer value for scenarioNumber in scenario file. Received: '" +
                            scenarioNumber.toString() + "'. Set to 0.");
                    // state.scenarioNumber initialised with default value of 0
                }
            }

            Double windSet = GsonUtils.getValue(obj, "windSet");
            List<Object> windList;
            if (windSet != null) {
                String windSetJson = "";
                if (windSet == 1.0) {
                    windSetJson = GsonUtils.readFile(webRef + "/scenarios/snippets/WindSet1.json");
                } else if (windSet == 2.0) {
                    windSetJson = GsonUtils.readFile(webRef + "/scenarios/snippets/WindSet2.json");
                }
                Object windSetObj = GsonUtils.fromJson(windSetJson);
                windList = GsonUtils.getValue(windSetObj, "wind");
            } else {
                windList = GsonUtils.getValue(obj, "wind");
            }
            if (windList != null) {
                for (Object wind : windList) {
                    Double time = GsonUtils.getValue(wind, "time");
                    Double speed = GsonUtils.getValue(wind, "speed");
                    Double heading = GsonUtils.getValue(wind, "heading");
                    this.state.addFutureWind(time, speed, heading);
                }
            }

            this.state.resetNext();
            if(GsonUtils.hasKey(obj,"timeLimitSeconds")){
                Object timeLimitSeconds = GsonUtils.getValue(obj, "timeLimitSeconds");
                if(timeLimitSeconds.getClass() == Double.class) {
                    state.incrementTimeLimit((Double)timeLimitSeconds);
                } else {
                    LOGGER.warning("Expected double value for timeLimitSeconds in scenario file. Received: '" +
                            timeLimitSeconds.toString() + "'. Time limit not changed.");
                }
            }
            if(GsonUtils.hasKey(obj,"timeLimitMinutes")){
                Object timeLimitMinutes = GsonUtils.getValue(obj, "timeLimitMinutes");
                if(timeLimitMinutes.getClass() == Double.class) {
                    state.incrementTimeLimit((Double)timeLimitMinutes * 60);
                } else {
                    LOGGER.warning("Expected double value for timeLimitMinutes in scenario file. Received: '" +
                            timeLimitMinutes.toString() + "'. Time limit not changed.");
                }
            }
            if(GsonUtils.hasKey(obj,"nextScenarioFile")){
                Object nextScenarioFile = GsonUtils.getValue(obj, "nextScenarioFile");
                if(nextScenarioFile.getClass() == String.class) {
                    this.state.setPassthrough(true);
                    state.setNextFileName(nextScenarioFile.toString());
                }
            }

            if(GsonUtils.hasKey(obj,"deepAllowed")) {
                Object deepAllowed = GsonUtils.getValue(obj, "deepAllowed");
                if (deepAllowed.getClass() == Boolean.class) {
                    state.setDeepAllowed((Boolean) deepAllowed);
                }
            }

            List<Object> agentsJson = GsonUtils.getValue(obj, "agents");
            if (agentsJson != null) {
                for (Object agentJSon : agentsJson) {
                    Double lat = GsonUtils.getValue(agentJSon, "lat");
                    Double lng = GsonUtils.getValue(agentJSon, "lng");
                    Agent agent = agentController.addVirtualAgent(lat, lng, 0);
                    Double battery = GsonUtils.getValue(agentJSon, "battery");
                    if(battery != null)
                        agent.setBattery(battery);
                }
            }

            List<Object> hazards = GsonUtils.getValue(obj, "hazards");
            if (hazards != null) {
                for (Object hazard : hazards) {
                    Double lat = GsonUtils.getValue(hazard, "lat");
                    Double lng = GsonUtils.getValue(hazard, "lng");
                    int type = ((Double) GsonUtils.getValue(hazard, "type")).intValue();
                    if (GsonUtils.hasKey(hazard, "size")) {
                        int size = ((Double) GsonUtils.getValue(hazard, "size")).intValue();
                        hazardController.addHazard(lat, lng, type, size);
                    } else {
                        hazardController.addHazard(lat, lng, type);
                    }
                }
            }

            Double targetSet = GsonUtils.getValue(obj, "targetSet");
            Double imageSet = GsonUtils.getValue(obj, "imageSet");
            List<Object> targetsJson;
            List<Object> imagesJson = new ArrayList<>();
            if (targetSet != null && imageSet != null) {
                String targetSetJson = "";
                if (targetSet == 1.0) {
                    targetSetJson = GsonUtils.readFile(webRef + "/scenarios/snippets/TargetSet1.json");
                } else if (targetSet == 2.0) {
                    targetSetJson = GsonUtils.readFile(webRef + "/scenarios/snippets/TargetSet2.json");
                }
                Object targetSetObj = GsonUtils.fromJson(targetSetJson);
                targetsJson = GsonUtils.getValue(targetSetObj, "targets");

                String imageSetJson = "";
                if (imageSet == 1.0) {
                    imageSetJson = GsonUtils.readFile(webRef + "/scenarios/snippets/ImageSet1.json");
                } else if (imageSet == 2.0) {
                    imageSetJson = GsonUtils.readFile(webRef + "/scenarios/snippets/ImageSet2.json");
                }
                Object imageSetObj = GsonUtils.fromJson(imageSetJson);
                imagesJson = GsonUtils.getValue(imageSetObj, "images");
            } else {
                targetsJson = GsonUtils.getValue(obj, "targets");
            }
            
            if (targetsJson != null) {
                for (int i = 0; i < targetsJson.size(); i++) {
                    Object targetJson = targetsJson.get(i);
                    Double lat = GsonUtils.getValue(targetJson, "lat");
                    Double lng = GsonUtils.getValue(targetJson, "lng");
                    int type = ((Double) GsonUtils.getValue(targetJson, "type")).intValue();

                    Object imageJson;
                    if (imageSet != null) {
                        imageJson = imagesJson.get(i);
                    } else {
                        imageJson = targetJson;
                    }
                    String highRes = GsonUtils.getValue(imageJson, "highRes");
                    String lowRes = GsonUtils.getValue(imageJson, "lowRes");
                    Target target = null;
                    if (GsonUtils.hasKey(imageJson, "correctClassification")) {
                        String correctClassification = GsonUtils.getValue(imageJson, "correctClassification");
                        if (!correctClassification.equals("false") || (faultySwarm != null && faultySwarm)) {
                            // if the swarm is not faulty, don't add false alarm targets
                            target = targetController.addTarget(lat, lng, type, correctClassification);
                            ((AdjustableTarget) target).setFilenames(lowRes, highRes);
                        }
                    } else {
                        target = targetController.addTarget(lat, lng, type);
                    }

                    if (target != null) {
                        //Hide all targets initially - they must be found!!
                        targetController.setTargetVisibility(target.getId(), false);
                    }
                }
            }

            Object uiJson = GsonUtils.getValue(obj, "extendedUIOptions");
            if (uiJson != null) {
                if (GsonUtils.getValue(uiJson, "predictions") != null && (boolean) GsonUtils.getValue(uiJson, "predictions")) {
                    state.addUIOption("predictions");
                }
                if (GsonUtils.getValue(uiJson, "uncertainties") != null && (boolean) GsonUtils.getValue(uiJson, "uncertainties")) {
                    state.addUIOption("uncertainties");
                }
            }

            if(GsonUtils.hasKey(obj,"uncertaintyRadius")) {
                this.state.setUncertaintyRadius(GsonUtils.getValue(obj, "uncertaintyRadius"));
            }

            List<Object> markers = GsonUtils.getValue(obj, "markers");
            if (markers != null) {
                for (Object markerJson : markers) {
                    String shape = GsonUtils.getValue(markerJson, "shape");
                    Double cLat = GsonUtils.getValue(markerJson, "centreLat");
                    Double cLng = GsonUtils.getValue(markerJson, "centreLng");
                    Double radius = GsonUtils.getValue(markerJson, "radius");

                    String shapeRep = shape+","+cLat+","+cLng+","+radius;

                    this.state.getMarkers().add(shapeRep);
                }
            }
            LOGGER.info(String.format("%s; SCINIT; Scenario initialised with the following conditions (scenarioNumber, chatEnabled, faultySwarm, targetSet, imageSet, windSet); %s, %s, %s, %s, %s, %s ", getState().getTime(), scenarioNumber, chatEnabled, faultySwarm, targetSet, imageSet, windSet));
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    private String getScenarioNameFromFile(String scenarioFile) {
        try {
            String json = GsonUtils.readFile(scenarioFile);
            Object obj = GsonUtils.fromJson(json);
            return GsonUtils.getValue(obj, "gameId");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static boolean sleep(long millis) {
        try {
            if (millis > 0) {
                Thread.sleep(millis);
            }
            return true;
        } catch (InterruptedException e) {
            // TODO make this more graceful when state is reset
            e.printStackTrace();
            return false;
        }
    }

    public synchronized String getStateAsString() {
        return state.toString();
    }

    public synchronized State getState() {
        return state;
    }

    public Allocator getAllocator() {
        return this.allocator;
    }

    public AgentController getAgentController() {
        return agentController;
    }

    public TaskController getTaskController() {
        return taskController;
    }

    public TargetController getTargetController() {
        return targetController;
    }

    public QueueManager getQueueManager() {
        return queueManager;
    }

    public ImageController getImageController() {
        return imageController;
    }

}
