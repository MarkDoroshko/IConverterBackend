package ru.iconverter.services;

import org.springframework.http.MediaType;

public interface IMediaTypeService {

    MediaType getMediaType(String format);
}
