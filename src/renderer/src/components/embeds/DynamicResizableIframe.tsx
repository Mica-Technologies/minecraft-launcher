import React, { useMemo } from 'react';
import { Container } from '@mui/material';
import { useSearchParams } from 'react-router-dom';

interface DynamicResizableIframeProps {
  url: string;
  title?: string;
  passUrlParams?: boolean | string[];
  excludeParams?: string[];
}

const DynamicResizableIframe: React.FC<DynamicResizableIframeProps> = ({
  url,
  title,
  passUrlParams,
  excludeParams = [],
}) => {
  const [searchParams] = useSearchParams();

  // Build the query string to pass to the iframe
  const iframeSrc = useMemo(() => {
    if (!passUrlParams) return url;

    const params = new URLSearchParams();
    for (const [key, value] of searchParams.entries()) {
      if (
        (passUrlParams === true || (Array.isArray(passUrlParams) && passUrlParams.includes(key))) &&
        !excludeParams.includes(key)
      ) {
        params.append(key, value);
      }
    }
    const query = params.toString();
    return query ? `${url}${url.includes('?') ? '&' : '?'}${query}` : url;
  }, [url, passUrlParams, excludeParams, searchParams]);

  return (
    <Container
      className="DynamicResizableIframe"
      component="div"
      disableGutters
      sx={{
        width: '100%',
        height: '100%',
        flex: 1,
        display: 'flex',
        flexDirection: 'column',
        minHeight: 0,
        minWidth: 0,
        p: 0,
        maxWidth: 'unset !important',
        maxHeight: 'unset !important',
      }}
    >
      <div
        style={{
          overflow: 'auto',
          minWidth: 0,
          minHeight: 0,
          width: '100%',
          height: '100%',
          border: '1px solid #ccc',
          position: 'relative',
          flex: 1,
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        <iframe
          src={iframeSrc}
          style={{
            width: '100%',
            height: '100%',
            border: 'none',
            display: 'block',
            flex: 1,
            minWidth: 0,
          }}
          title={title ? title : 'Dynamic Resizable Iframe'}
          allowFullScreen
        />
      </div>
    </Container>
  );
};

export default DynamicResizableIframe;
