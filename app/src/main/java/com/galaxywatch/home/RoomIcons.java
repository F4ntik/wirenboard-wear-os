package com.galaxywatch.home;

record RoomIcons(int icon, int crown, int surface) {
    static RoomIcons forRoom(String id) {
        return switch (id) {
            case "living" -> new RoomIcons(R.drawable.ic_room_living, R.drawable.crown_living, 0xFF48303A);
            case "kitchen" -> new RoomIcons(R.drawable.ic_room_kitchen, R.drawable.crown_kitchen, 0xFF323C35);
            case "bedroom" -> new RoomIcons(R.drawable.ic_room_bedroom, R.drawable.blossoms, 0xFF402F40);
            case "hall" -> new RoomIcons(R.drawable.ic_room_hall, R.drawable.crown_hall, 0xFF393C33);
            case "kids" -> new RoomIcons(R.drawable.ic_room_kids, R.drawable.crown_kids, 0xFF44363E);
            case "office" -> new RoomIcons(R.drawable.ic_room_office, R.drawable.crown_office, 0xFF2D3B41);
            default -> new RoomIcons(R.drawable.ic_room_grid, R.drawable.blossoms, 0xFF302631);
        };
    }
}
