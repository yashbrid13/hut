package server.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import server.Allocator;
import server.model.hazard.Hazard;
import server.model.target.Target;
import server.model.task.Task;
import tool.GsonUtils;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.FileHandler;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class State {

    public static final int GAME_TYPE_SANDBOX = 0;
    public static final int GAME_TYPE_SCENARIO = 1;
    private final transient Logger LOGGER;
    private boolean inProgress;

    private String gameId;
    private String gameDescription;
    private int gameType;
    private String allocationMethod;
    private Boolean flockingEnabled;
    private double time;
    private double timeLimit;
    private long scenarioEndTime;
    private long scenarioStartTime;
    private int editMode;
    // editMode 1 = monitor
    //          2 = edit
    //          3 = images
    private boolean passthrough = false;
    private String nextFileName = "";
    private boolean deepAllowed = false;

    private String prov_doc;

    private final Collection<Agent> agents;
    private final Collection<Task> tasks;
    private final Collection<Task> completedTasks;
    private final Collection<Hazard> hazards;

    //State information for scenarios
    private Coordinate gameCentre;
    private final Collection<Target> targets;

    //Updated on server but only used on client.
    @SuppressWarnings("unused")
    private boolean allocationUndoAvailable;
    @SuppressWarnings("unused")
    private boolean allocationRedoAvailable;

    private Map<String, String> allocation;
    //Allocation that is WIP (i.e. not confirmed by user).
    private Map<String, String> tempAllocation;
    //Allocation created from dropped out agents.
    private Map<String, String> droppedAllocation;

    private HazardHitCollection hazardHits;

    private ArrayList<String> uiOptions = new ArrayList<>();
    private double uncertaintyRadius = 0;

    private Coordinate hubLocation;

    //                   ID->ImageName
    private final Map<String, String> storedImages = new ConcurrentHashMap<>(16);
    private final List<String> deepScannedIds = new ArrayList<>(16);
    private final List<String> pendingIds = new ArrayList<>(16);

    private Double windSpeed;
    private Double windHeading;
    private List<HashMap> futureWind = new ArrayList<>();

    private List<String> userNames = new ArrayList<>();
    private List<String> markers = new ArrayList<>();

    private List<String> chatLog = new ArrayList<>();
    private Boolean chatEnabled;

    private int requiredUsers;
    private int readyUsers;

    private int completedSurveys = 0;
    private int scenarioNumber;

    private Double avgAgentDropout;
    private Double ignoredTaskProb;

    private Boolean abandoned = false;

    public State(Logger LOGGER) {
        this.LOGGER = LOGGER;
        agents = new ArrayList<>();
        tasks = new ArrayList<>();
        completedTasks = new ArrayList<>();
        targets = new ArrayList<>();
        hazards = new ArrayList<>();
        allocation = new ConcurrentHashMap<>();
        tempAllocation = new ConcurrentHashMap<>();
        droppedAllocation = new ConcurrentHashMap<>();
        hazardHits = new HazardHitCollection();

        allocationUndoAvailable = false;
        allocationRedoAvailable = false;

        reset();
    }

    public synchronized void reset() {
        // Define defaults
        time = 0;
        editMode = 1;
        inProgress = false;
        allocationMethod = "maxsum";
        flockingEnabled = false;
        uncertaintyRadius = 0;
        windSpeed = 0.0;
        windHeading = 0.0;
        markers.clear();

        scenarioNumber = 0;

        gameCentre = null;
        userNames.clear();

        chatLog.clear();
        chatLog.add("Chat Connected.");
        chatEnabled = false;

//        if (completedSurveys == requiredUsers) {
//            completedSurveys = 0;
//        }

        requiredUsers = 2; // Just for testing
        readyUsers = 0;

        avgAgentDropout = 0.0;
        ignoredTaskProb = 0.0;

        agents.clear();
        tasks.clear();
        completedTasks.clear();
        targets.clear();
        hazards.clear();
        allocation.clear();
        tempAllocation.clear();
        hazardHits.clear();

        storedImages.clear();
        uiOptions.clear();

        hazardHits.init();
    }

    public void resetNext() {
        passthrough = false;
        nextFileName = "";
        scenarioEndTime = 0; // 0 means no time limit
        timeLimit = 0;    // 0 means no time limit
    }

    @Override
    public synchronized String toString() {
        return GsonUtils.toJson(this);
    }

    public Target getTarget(String targetId) {
        return getById(targets, targetId);
    }

    public Task getTask(String taskId) {
        return getById(tasks, taskId);
    }

    public Agent getAgent(String agentId) {
        return getById(agents, agentId);
    }

    public Hazard getHazard(String hazardId) {
        return getById(hazards, hazardId);
    }

    public void add(IdObject item) {
        if(item instanceof Target)
            add(targets, (Target) item);
        else if(item instanceof  Task)
            add(tasks, (Task) item);
        else if(item instanceof Agent)
            add(agents, (Agent) item);
        else if(item instanceof Hazard)
            add(hazards, (Hazard) item);
        else
            throw new RuntimeException("Cannot add item to state, unrecognised class - " + item.getClass().getSimpleName());
    }

    public void remove(IdObject item) {
        if(item instanceof Target)
            remove(targets, (Target) item);
        else if(item instanceof  Task)
            remove(tasks, (Task) item);
        else if(item instanceof  Agent)
            remove(agents, (Agent) item);
        else
            throw new RuntimeException("Cannot remove item from state, unrecognised class - " + item.getClass().getSimpleName());
    }

    private <T extends IdObject> void add(Collection<T> items, T item) {
        if(getById(items, item.getId()) != null)
            throw new RuntimeException("Cannot add item to list - list already contains item with given id.");
        items.add(item);
    }

    private <T extends IdObject> boolean remove(Collection<T> items, T item) {
        if((item = getById(items, item.getId())) == null)
            return false;
        items.remove(item);
        return true;
    }

    private <T extends IdObject> T getById(Collection<T> items, String id) {
        List<T> matching = items.stream().filter(o -> o.getId().equals(id)).collect(Collectors.toList());
        if(matching.size() == 0)
            return null;
        if(matching.size() > 1)
            throw new RuntimeException("Two objects found with same id!");
        return matching.get(0);
    }

    //Getters and setters below
    public synchronized double getTime() {
        return time;
    }

    public synchronized void setTime(double time) {
        this.time = time;
    }

    public synchronized void incrementTime(double increment) {
        setTime(this.time + increment);
    }

    public synchronized double getTimeLimit() {
        return timeLimit;
    }

    public synchronized void setTimeLimit(double timeLimit) {
        this.timeLimit = timeLimit;
        this.setScenarioEndTime(timeLimit);
    }

    public synchronized void incrementTimeLimit(double increment) {
        setTimeLimit(this.timeLimit + increment);
        this.setScenarioEndTime(timeLimit);
    }

    public synchronized long getScenarioEndTime() {
        return scenarioEndTime;
    }

    public synchronized void setScenarioEndTime() {
        if (this.timeLimit == 0) {
            this.scenarioEndTime = 0;
        } else {
            this.scenarioEndTime = System.currentTimeMillis() + (long)(this.timeLimit * 1000);
        }
    }

    public synchronized void setScenarioStartTime() {
        this.scenarioStartTime = System.currentTimeMillis();
    }

    public synchronized long getScenarioStartTime() {
        return scenarioStartTime;
    }

    private synchronized void setScenarioEndTime(double timeLimit) {
        if (timeLimit == 0) {
            this.scenarioEndTime = 0;
        } else {
            this.scenarioEndTime = System.currentTimeMillis() + (long) (timeLimit * 1000);
        }
    }

    public synchronized int getEditMode() {
        return editMode;
    }

    public synchronized void setEditMode(int editMode) {
        this.editMode = editMode;
    }

    public synchronized String getGameId() {
        return gameId;
    }

    public synchronized void setGameId(String gameId) {
        this.gameId = gameId;
    }

    public synchronized int getGameType() {
        return gameType;
    }

    public String getGameDescription() {
        return gameDescription;
    }

    public void setGameDescription(String gameDescription) {
        this.gameDescription = gameDescription;
    }

    public synchronized void setGameType(int gameType) {
        this.gameType = gameType;
    }

    public synchronized void setGameCentre(Coordinate gameCentre) {
        this.gameCentre = gameCentre;
    }

    public synchronized void setAllocationMethod(String allocationMethod) {
        this.allocationMethod = allocationMethod;
    }

    public synchronized String getAllocationMethod() {
        return this.allocationMethod;
    }

    public synchronized void setFlockingEnabled(Boolean flockingEnabled) {
        this.flockingEnabled = flockingEnabled;
    }

    public synchronized Boolean isFlockingEnabled() {
        return this.flockingEnabled;
    }

    public Collection<Target> getTargets() {
        return targets;
    }

    public Collection<Task> getTasks() {
        synchronized (tasks) {
            return tasks;
        }
    }

    public Collection<Agent> getAgents() {
        return agents;
    }

    public Map<String, String> getAllocation() {
        return allocation;
    }

    public Collection<Hazard> getHazards() {
        return hazards;
    }

    public void setAllocation(Map<String, String> allocation) {
        this.allocation = allocation;
    }

    public Map<String, String> getTempAllocation() {
        return tempAllocation;
    }

    public void setTempAllocation(Map<String, String> tempAllocation) {
        if(tempAllocation == null)
            this.tempAllocation.clear();
        else
            this.tempAllocation = tempAllocation;
    }

    public Map<String, String> getDroppedAllocation() {
        return droppedAllocation;
    }

    public synchronized void setProvDoc(String prov_doc) {
        this.prov_doc = prov_doc;
    }

    public void setAllocationUndoAvailable(boolean allocationUndoAvailable) {
        this.allocationUndoAvailable = allocationUndoAvailable;
    }

    public void setAllocationRedoAvailable(boolean allocationRedoAvailable) {
        this.allocationRedoAvailable = allocationRedoAvailable;
    }

    public void addCompletedTask(Task task) {
        this.completedTasks.add(task);
    }

    public boolean isInProgress() {
        return inProgress;
    }

    public void setInProgress(boolean inProgress) {
        this.inProgress = inProgress;
    }

    public synchronized void addHazardHit(int type, Coordinate location) {
        hazardHits.add(type, location);
    }

    public void decayHazardHits() {
        hazardHits.decayAll();
    }

    public void setPassthrough(boolean passthrough) {
        this.passthrough = passthrough;
    }

    public boolean isPassthrough() {
        return passthrough;
    }

    public String getNextFileName() {
        return nextFileName;
    }

    public void setNextFileName(String nextFileName) {
        this.nextFileName = nextFileName;
    }

    public void addUIOption(String option) {
        uiOptions.add(option);
    }

    public void setUncertaintyRadius(double uncertaintyRadius) {
        this.uncertaintyRadius = uncertaintyRadius;
    }

    public void setHubLocation(Coordinate coordinate) {
        hubLocation = coordinate;
    }

    public Coordinate getHubLocation() {
        return hubLocation;
    }

    public Map<String, String> getStoredImages() {
        return storedImages;
    }

    public void addToStoredImages(String id, String filename, boolean isDeep) {
        storedImages.put(id, filename);
        if (isDeep) {
            deepScannedIds.add(id);
        }
    }

    public void setDeepAllowed(Boolean deepAllowed) {
        this.deepAllowed = deepAllowed;
    }

    public boolean addUserName(String userName) {
        this.userNames.add(userName);
        return true;
    }

    public List<String> getUserNames() {
        return userNames;
    }

    public boolean addToChatLog(String message) {
        this.chatLog.add(message);
        return true;
    }

    public List<String> getMarkers() {
        return markers;
    }

    public List<String> getPendingIds() {
        return pendingIds;
    }

    public void incrementReadyUsers() {
        this.readyUsers += 1;
    }

    public boolean usersReady() {
        return (this.readyUsers == this.requiredUsers);
    }

    public void incrementCompletedSurveys() {
        this.completedSurveys += 1;
    }

    public void resetLogger(FileHandler fileHandler) {
        LOGGER.addHandler(fileHandler);
    }

    public void setWindSpeed(Double windSpeed) {
        this.windSpeed = windSpeed;
    }

    public Double getWindSpeed() {
        return windSpeed;
    }

    public void setWindHeading(Double windHeading) {
        this.windHeading = windHeading;
    }

    public Double getWindHeading() {
        return windHeading;
    }

    public void setAvgAgentDropout(Double avgAgentDropout) {
        this.avgAgentDropout = avgAgentDropout;
    }

    public Double getAvgAgentDropout() {
        return this.avgAgentDropout;
    }

    public void setIgnoredTaskProb(Double ignoredTaskProb) {
        this.ignoredTaskProb = ignoredTaskProb;
    }

    public Double getIgnoredTaskProb() {
        return this.ignoredTaskProb;
    }

    public void setChatEnabled(Boolean chatEnabled) {
        this.chatEnabled = chatEnabled;
    }

    public void setScenarioNumber(int scenarioNumber) {
        this.scenarioNumber = scenarioNumber;
    }

    public void addFutureWind(Double time, Double speed, Double heading) {
        HashMap<String, Double> wind = new HashMap<>();
        wind.put("time", time);
        wind.put("speed", speed);
        wind.put("heading", heading);
        this.futureWind.add(wind);
    }

    public List<HashMap> getFutureWind() {
        return this.futureWind;
    }

    public void abandonScenario() {
        this.abandoned = true;
    }

    private class HazardHit {
        private Coordinate location;
        private double weight;
        private transient double decayRate;

        private HazardHit(Coordinate location, double decayRate) {
            this.location = location;
            this.weight = 1;
            this.decayRate = decayRate;
        }

        private boolean decay() {
            this.weight -= decayRate;
            return this.weight < 0;
        }
    }

    public class HazardHitCollection {
        private transient Map<Integer, Map<Coordinate, HazardHit>> hazardHits;

        private HazardHitCollection() {
            this.hazardHits = new ConcurrentHashMap<>();
        }

        private void init() {
            hazardHits.put(Hazard.NONE, new ConcurrentHashMap<>());
            hazardHits.put(Hazard.FIRE, new ConcurrentHashMap<>());
            hazardHits.put(Hazard.DEBRIS, new ConcurrentHashMap<>());
        }

        private void add(int type, Coordinate location) {
            if(this.hazardHits.containsKey(type)) {
                /* Hits should only be registered if they are far enough from all
                 * other hits. This is done by storing a rounded coordinate to provide
                 * a quick way to see if a hit is far enough away from all the other hits.
                 *
                 * The actual coordinate is kept, and this is the one that should be rendered
                 * so the heatmap does not appear 'blocky'.
                 */
                double lat = location.latitude;
                double lng = location.longitude;
                double roundedLat = Math.round(lat * 10000D) / 10000D;
                double roundedLng = Math.round(lng * 10000D) / 10000D;
                Coordinate roundedCoord = new Coordinate(roundedLat, roundedLng);
                Map<Coordinate, HazardHit> coordMap = this.hazardHits.get(type);
                HazardHit hit;
                if(type == -1)
                    hit = new HazardHit(location, 0.001);
                else
                    hit = new HazardHit(location, 0);
                coordMap.put(roundedCoord, hit);
            }
            else {
                LOGGER.severe("Could not register hazard hit - not list for hazard type " + type);
            }
        }

        private void decayAll() {
            for(Map<Coordinate, HazardHit> m : hazardHits.values()) {
                for(Map.Entry<Coordinate, HazardHit> e : m.entrySet())
                    if(e.getValue().decay())
                        m.remove(e.getKey());
            }
        }

        private void clear() {
            hazardHits.clear();
        }
    }

    public static JsonSerializer hazardHitsSerializer = new JsonSerializer<HazardHitCollection>() {
        @Override
        public JsonElement serialize(HazardHitCollection hazardHitCollection, Type type, JsonSerializationContext context) {
            JsonObject jsonObject = new JsonObject();
            jsonObject.add("-1", context.serialize(hazardHitCollection.hazardHits.get(-1).values()));
            jsonObject.add("0", context.serialize(hazardHitCollection.hazardHits.get(0).values()));
            jsonObject.add("1", context.serialize(hazardHitCollection.hazardHits.get(1).values()));
            return jsonObject;
        }
    };
}
