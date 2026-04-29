package com.example.myserver;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

public class MyClass {

    private static final String DIFFICULTY_EASY = "EASY";
    private static final String DIFFICULTY_NORMAL = "NORMAL";
    private static final String DIFFICULTY_HARD = "HARD";
    private static final int EASY_PORT = 9999;
    private static final int NORMAL_PORT = 12000;
    private static final int HARD_PORT = 10001;
    private static final String PHASE_ACTIVE = "ACTIVE";
    private static final String PHASE_RESULT = "RESULT";

    private final Map<String, Queue<Service>> waitingPlayers = new HashMap<>();

    public static void main(String[] args) {
        new MyClass();
    }

    public MyClass() {
        startAcceptLoop(EASY_PORT, DIFFICULTY_EASY);
        startAcceptLoop(NORMAL_PORT, DIFFICULTY_NORMAL);
        startAcceptLoop(HARD_PORT, DIFFICULTY_HARD);
    }

    private void startAcceptLoop(int port, String difficulty) {
        Thread acceptThread = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("Aircraft War socket server started on port "
                        + port + " for " + difficulty);
                while (true) {
                    System.out.println("waiting client connect on " + difficulty + "@" + port);
                    Socket socket = serverSocket.accept();
                    System.out.println("accept client connect " + socket + " on " + difficulty);
                    new Thread(new Service(socket, difficulty), "aircraft-war-" + difficulty.toLowerCase(Locale.ROOT))
                            .start();
                }
            } catch (IOException exception) {
                exception.printStackTrace();
            }
        }, "accept-" + difficulty.toLowerCase(Locale.ROOT));
        acceptThread.start();
    }

    private synchronized void joinMatch(Service player, String difficulty) {
        String normalizedDifficulty = normalizeDifficulty(difficulty);
        if (normalizedDifficulty == null) {
            player.sendMessage("ERROR|invalid difficulty");
            return;
        }
        if (player.isInQueueOrRoom()) {
            player.sendMessage("ERROR|already joined");
            return;
        }

        Queue<Service> queue = waitingPlayers.computeIfAbsent(normalizedDifficulty, key -> new ArrayDeque<>());
        Service opponent = pollAvailablePlayer(queue);
        if (opponent == null) {
            player.waitForDifficulty(normalizedDifficulty);
            queue.offer(player);
            player.sendMessage("WAITING|" + normalizedDifficulty);
            return;
        }

        MatchRoom room = new MatchRoom(normalizedDifficulty, opponent, player);
        room.start();
    }

    private synchronized void removeWaitingPlayer(Service player) {
        String difficulty = player.getDifficulty();
        if (difficulty == null) {
            return;
        }
        Queue<Service> queue = waitingPlayers.get(difficulty);
        if (queue != null) {
            queue.remove(player);
        }
    }

    private Service pollAvailablePlayer(Queue<Service> queue) {
        while (!queue.isEmpty()) {
            Service player = queue.poll();
            if (!player.isClosed() && player.isWaiting()) {
                return player;
            }
        }
        return null;
    }

    private String normalizeDifficulty(String difficulty) {
        if (difficulty == null || difficulty.trim().isEmpty()) {
            return DIFFICULTY_EASY;
        }
        String normalized = difficulty.trim().toUpperCase(Locale.ROOT);
        switch (normalized) {
            case "EASY":
            case "SIMPLE":
                return DIFFICULTY_EASY;
            case "NORMAL":
            case "MEDIUM":
                return DIFFICULTY_NORMAL;
            case "HARD":
                return DIFFICULTY_HARD;
            default:
                return null;
        }
    }

    private final class Service implements Runnable {
        private final Socket socket;
        private final String listenerDifficulty;
        private BufferedReader in;
        private PrintWriter pout;
        private MatchRoom room;
        private String difficulty;
        private boolean waiting;
        private boolean closed;

        Service(Socket socket, String listenerDifficulty) {
            this.socket = socket;
            this.listenerDifficulty = listenerDifficulty;
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                pout = new PrintWriter(new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)), true);
            } catch (IOException exception) {
                exception.printStackTrace();
                close();
            }
        }

        @Override
        public void run() {
            sendMessage("CONNECTED|AircraftWarSocketServer");
            try {
                String content;
                while (!isClosed() && (content = in.readLine()) != null) {
                    handleMessage(content);
                }
            } catch (IOException exception) {
                if (!isClosed()) {
                    System.out.println("client disconnected: " + exception.getMessage());
                }
            } finally {
                close();
            }
        }

        private void handleMessage(String content) {
            if (content == null || content.trim().isEmpty()) {
                return;
            }
            String[] parts = content.trim().split("\\|");
            String command = parts[0].toUpperCase(Locale.ROOT);
            switch (command) {
                case "JOIN":
                case "MATCH":
                    handleJoinMessage(parts);
                    break;
                case "SCORE":
                    handleScoreMessage(parts);
                    break;
                case "DEAD":
                case "GAME_OVER":
                    handleDeadMessage(parts);
                    break;
                case "QUIT":
                case "BYE":
                    close();
                    break;
                case "PING":
                    sendMessage("PONG");
                    break;
                default:
                    sendMessage("ERROR|unsupported command");
                    break;
            }
        }

        private void handleJoinMessage(String[] parts) {
            String requestedDifficulty = listenerDifficulty;
            if (parts.length >= 2) {
                requestedDifficulty = normalizeDifficulty(parts[1]);
            }
            if (requestedDifficulty == null) {
                sendMessage("ERROR|invalid difficulty");
                return;
            }
            if (!listenerDifficulty.equals(requestedDifficulty)) {
                sendMessage("ERROR|difficulty mismatch");
                return;
            }
            joinMatch(this, listenerDifficulty);
        }

        private void handleScoreMessage(String[] parts) {
            if (parts.length < 2) {
                sendMessage("ERROR|bad score payload");
                return;
            }
            Integer score = parseScore(parts[1]);
            if (score == null) {
                sendMessage("ERROR|bad score payload");
                return;
            }
            MatchRoom currentRoom = getRoom();
            if (currentRoom == null) {
                sendMessage("ERROR|not matched");
                return;
            }
            currentRoom.updateScore(this, score);
        }

        private void handleDeadMessage(String[] parts) {
            if (parts.length < 2) {
                sendMessage("ERROR|bad dead payload");
                return;
            }
            Integer score = parseScore(parts[1]);
            if (score == null) {
                sendMessage("ERROR|bad dead payload");
                return;
            }
            MatchRoom currentRoom = getRoom();
            if (currentRoom == null) {
                sendMessage("ERROR|not matched");
                return;
            }
            currentRoom.markDead(this, score);
        }

        private Integer parseScore(String value) {
            try {
                return Math.max(0, Integer.parseInt(value));
            } catch (NumberFormatException exception) {
                return null;
            }
        }

        synchronized void waitForDifficulty(String difficulty) {
            this.difficulty = difficulty;
            this.waiting = true;
        }

        synchronized void enterRoom(MatchRoom room) {
            this.room = room;
            this.waiting = false;
            this.difficulty = room.getDifficulty();
        }

        synchronized MatchRoom getRoom() {
            return room;
        }

        synchronized String getDifficulty() {
            return difficulty;
        }

        synchronized boolean isWaiting() {
            return waiting;
        }

        synchronized boolean isInQueueOrRoom() {
            return waiting || room != null;
        }

        synchronized boolean isClosed() {
            return closed;
        }

        void sendMessage(String message) {
            synchronized (this) {
                if (closed || pout == null) {
                    return;
                }
                System.out.println("message to client: " + message);
                pout.println(message);
            }
        }

        void close() {
            MatchRoom currentRoom;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                waiting = false;
                currentRoom = room;
            }
            removeWaitingPlayer(this);
            if (currentRoom != null) {
                currentRoom.disconnect(this);
            }
            closeQuietly(in);
            if (pout != null) {
                pout.close();
            }
            closeQuietly(socket);
        }
    }

    private static final class MatchRoom {
        private final String roomId = UUID.randomUUID().toString();
        private final String difficulty;
        private final Service[] players = new Service[2];
        private final int[] scores = new int[2];
        private final boolean[] alive = new boolean[]{true, true};
        private boolean finished;

        MatchRoom(String difficulty, Service playerOne, Service playerTwo) {
            this.difficulty = difficulty;
            players[0] = playerOne;
            players[1] = playerTwo;
        }

        String getDifficulty() {
            return difficulty;
        }

        synchronized void start() {
            players[0].enterRoom(this);
            players[1].enterRoom(this);
            players[0].sendMessage("ASSIGN|1");
            players[1].sendMessage("ASSIGN|2");
            broadcast("MATCHED|" + difficulty + "|" + roomId);
            broadcastState();
            System.out.println("match started, difficulty=" + difficulty + ", room=" + roomId);
        }

        synchronized void updateScore(Service player, int score) {
            int index = indexOf(player);
            if (index < 0 || finished) {
                return;
            }
            scores[index] = score;
            broadcastState();
        }

        synchronized void markDead(Service player, int score) {
            int index = indexOf(player);
            if (index < 0 || finished) {
                return;
            }
            scores[index] = score;
            alive[index] = false;
            if (!alive[0] && !alive[1]) {
                finished = true;
                broadcastState();
                broadcast("RESULT|" + scores[0] + "|" + scores[1]);
                System.out.println("match finished, room=" + roomId
                        + ", p1=" + scores[0] + ", p2=" + scores[1]);
                return;
            }
            broadcastState();
        }

        synchronized void disconnect(Service player) {
            int index = indexOf(player);
            if (index < 0 || finished) {
                return;
            }
            finished = true;
            Service peer = players[index == 0 ? 1 : 0];
            if (peer != null && !peer.isClosed()) {
                peer.sendMessage("PEER_LEFT");
            }
            System.out.println("match closed because player left, room=" + roomId);
        }

        private int indexOf(Service player) {
            if (players[0] == player) {
                return 0;
            }
            if (players[1] == player) {
                return 1;
            }
            return -1;
        }

        private void broadcastState() {
            broadcast("STATE|" + scores[0] + "|" + alive[0]
                    + "|" + scores[1] + "|" + alive[1] + "|" + currentPhase());
        }

        private String currentPhase() {
            return finished ? PHASE_RESULT : PHASE_ACTIVE;
        }

        private void broadcast(String message) {
            for (Service player : players) {
                if (player != null) {
                    player.sendMessage(message);
                }
            }
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
